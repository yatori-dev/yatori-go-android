package mobile

import (
	"crypto/md5"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// VideoTaskMeta holds fields needed for video submission, extracted from the card HTML.
type VideoTaskMeta struct {
	JobID     string
	ObjectID  string
	OtherInfo string
}

// VideoDtoMeta holds dtoken and duration from the video DTO fetch.
type VideoDtoMeta struct {
	DToken   string
	Duration int
}

// ParseCardHTMLForVideoTask extracts VideoTaskMeta from card HTML.
// Searches for embedded attachment JSON containing jobId/otherInfo.
func ParseCardHTMLForVideoTask(html, targetObjectID string) (VideoTaskMeta, error) {
	// Extract attachment JSON from: window.AttachmentSetting = {...}; or similar patterns
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	if m := reAttach.FindStringSubmatch(html); len(m) > 1 {
		if meta, err := parseAttachmentJSON(m[1], targetObjectID); err == nil {
			return meta, nil
		}
	}
	// Fallback: search for JSON blocks containing jobid
	reJSON := regexp.MustCompile(`\{[^{}]*"jobid"\s*:\s*"[^"]+[^{}]*\}`)
	for _, m := range reJSON.FindAllString(html, -1) {
		var obj map[string]interface{}
		if err := json.Unmarshal([]byte(m), &obj); err != nil {
			continue
		}
		jobId := jsonStr(obj, "jobid", "_jobid")
		objectId := jsonStr(obj, "objectid", "objectId", "Objectid")
		if targetObjectID != "" && objectId != "" && objectId != targetObjectID {
			continue
		}
		if objectId == "" {
			objectId = targetObjectID
		}
		otherInfo := jsonStr(obj, "otherInfo", "otherinfo")
		if jobId != "" {
			return VideoTaskMeta{JobID: jobId, ObjectID: objectId, OtherInfo: otherInfo}, nil
		}
	}
	return VideoTaskMeta{}, fmt.Errorf("video task meta not found in card HTML for objectId=%q", targetObjectID)
}

func parseAttachmentJSON(raw, targetObjectID string) (VideoTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return VideoTaskMeta{}, err
	}
	attachments, _ := obj["attachments"].([]interface{})
	for _, a := range attachments {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		// objectid/jobid live inside "property" on real card payloads (the top-level objectId
		// exists only for videos); read property first, top-level as fallback. Matching by
		// objectid is what lets us skip a sibling document attachment on the same node.
		property, _ := m["property"].(map[string]interface{})
		objectId := firstNonEmptyString(jsonStr(m, "objectid", "objectId"), jsonStr(property, "objectid", "objectId"))
		if targetObjectID != "" && objectId != "" && objectId != targetObjectID {
			continue
		}
		jobId := firstNonEmptyString(jsonStr(m, "jobid", "_jobid"), jsonStr(property, "jobid", "_jobid"))
		// otherInfo carries a trailing "&courseId=..." that must be stripped before it is used
		// as a query value (the submit URL sets courseId separately); the original core trims it.
		otherInfo := jsonStr(m, "otherInfo", "otherinfo")
		if i := strings.IndexByte(otherInfo, '&'); i >= 0 {
			otherInfo = otherInfo[:i]
		}
		if jobId != "" {
			return VideoTaskMeta{JobID: jobId, ObjectID: objectId, OtherInfo: otherInfo}, nil
		}
	}
	return VideoTaskMeta{}, fmt.Errorf("not found")
}

// jsonStr extracts a string from a map trying multiple key names.
func jsonStr(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		switch v := m[k].(type) {
		case string:
			if v != "" {
				return v
			}
		case float64:
			return strconv.Itoa(int(v))
		}
	}
	return ""
}

// FetchVideoDtoApi fetches video dtoken and duration.
// fid is typically the classId (int) as used in the original protocol.
func (c *XxtClient) FetchVideoDtoApi(objectID string, fid int, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	params := url.Values{}
	params.Set("k", strconv.Itoa(fid))
	params.Set("flag", "normal")
	params.Set("_dc", strconv.FormatInt(time.Now().UnixMilli(), 10))
	req, err := http.NewRequest("GET", "https://mooc1-api.chaoxing.com/ananas/status/"+objectID+"?"+params.Encode(), nil)
	if err != nil {
		return c.FetchVideoDtoApi(objectID, fid, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.FetchVideoDtoApi(objectID, fid, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.FetchVideoDtoApi(objectID, fid, retry-1, err)
	}
	return string(body), nil
}

// ParseVideoDtoMeta extracts dtoken and duration from FetchVideoDtoApi JSON response.
func ParseVideoDtoMeta(raw string) (VideoDtoMeta, error) {
	var resp struct {
		Status   string  `json:"status"`
		DToken   string  `json:"dtoken"`
		Duration float64 `json:"duration"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return VideoDtoMeta{}, fmt.Errorf("video dto parse error: %w", err)
	}
	if resp.Status != "success" {
		return VideoDtoMeta{}, fmt.Errorf("video dto status=%q", resp.Status)
	}
	return VideoDtoMeta{DToken: resp.DToken, Duration: int(resp.Duration)}, nil
}

// VideoSubmitStudyTimeApi submits a single video study heartbeat.
// playingTime == duration means "completed"; isdrag=0 for normal end.
func (c *XxtClient) VideoSubmitStudyTimeApi(
	classID, userID, jobID, objectID, courseID, cpi, dtoken, otherInfo string,
	playingTime, duration, isdrag int, retry int, lastErr error,
) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	clipTime := fmt.Sprintf("0_%d", duration)
	hash := md5.Sum([]byte(fmt.Sprintf("[%s][%s][%s][%s][%d][%s][%d][%s]",
		classID, userID, jobID, objectID, playingTime*1000, "d_yHJ!$pdA~5", duration*1000, clipTime)))
	enc := hex.EncodeToString(hash[:])

	urlStr := fmt.Sprintf(
		"https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/%s/%s?clazzId=%s&playingTime=%d&duration=%d&clipTime=%s&objectId=%s&otherInfo=%s&courseId=%s&jobid=%s&userid=%s&isdrag=%d&view=json&enc=%s&rt=0.90&videoFaceCaptureEnc=&dtype=Video&_t=%d&attDuration=%d&attDurationEnc=",
		cpi, dtoken, classID, playingTime, duration, clipTime,
		objectID, url.QueryEscape(otherInfo), courseID, jobID, userID,
		isdrag, enc, time.Now().UnixMilli(), duration,
	)

	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.VideoSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, cpi, dtoken, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.VideoSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, cpi, dtoken, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.VideoSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, cpi, dtoken, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	return string(body), nil
}

type AudioSubmitParams struct {
	ClassID     string
	UserID      string
	JobID       string
	ObjectID    string
	CourseID    string
	OtherInfo   string
	PlayingTime int
	Duration    int
	IsDrag      int
	NowMillis   int64
}

func AudioSubmitStudyTimeURL(p AudioSubmitParams) (string, string) {
	if p.NowMillis == 0 {
		p.NowMillis = time.Now().UnixMilli()
	}
	clipTime := fmt.Sprintf("0_%d", p.Duration)
	hash := md5.Sum([]byte(fmt.Sprintf("[%s][%s][%s][%s][%d][%s][%d][%s]",
		p.ClassID, p.UserID, p.JobID, p.ObjectID, p.PlayingTime*1000, "d_yHJ!$pdA~5", p.Duration*1000, clipTime)))
	enc := hex.EncodeToString(hash[:])
	params := url.Values{}
	params.Set("objectId", p.ObjectID)
	params.Set("clazzId", p.ClassID)
	params.Set("userid", p.UserID)
	params.Set("jobid", p.JobID)
	params.Set("duration", strconv.Itoa(p.Duration))
	params.Set("otherInfo", p.OtherInfo)
	params.Set("courseId", p.CourseID)
	params.Set("dtype", "Audio")
	params.Set("view", "json")
	params.Set("playingTime", strconv.Itoa(p.PlayingTime))
	params.Set("isdrag", strconv.Itoa(p.IsDrag))
	params.Set("enc", enc)
	params.Set("_dc", strconv.FormatInt(p.NowMillis, 10))
	return "https://mooc1-api.chaoxing.com/mooc-ans/multimedia/log?" + params.Encode(), enc
}

func (c *XxtClient) AudioSubmitStudyTimeApi(
	classID, userID, jobID, objectID, courseID, otherInfo string,
	playingTime, duration, isdrag int, retry int, lastErr error,
) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr, _ := AudioSubmitStudyTimeURL(AudioSubmitParams{
		ClassID:     classID,
		UserID:      userID,
		JobID:       jobID,
		ObjectID:    objectID,
		CourseID:    courseID,
		OtherInfo:   otherInfo,
		PlayingTime: playingTime,
		Duration:    duration,
		IsDrag:      isdrag,
	})
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.AudioSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.AudioSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.AudioSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, otherInfo, playingTime, duration, isdrag, retry-1, err)
	}
	return string(body), nil
}
