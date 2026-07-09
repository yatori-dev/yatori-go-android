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
	JobID               string
	ObjectID            string
	OtherInfo           string
	Title               string
	PUID                string
	Mid                 string
	AttDurationEnc      string
	VideoFaceCaptureEnc string
	RandomCaptureTime   string
	FID                 int
	PlayTime            int
	AttDuration         int
	RT                  float64
	IsPassed            bool
	IsPassedKnown       bool
	IsJob               bool
	IsJobKnown          bool
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
			return VideoTaskMeta{JobID: jobId, ObjectID: objectId, OtherInfo: otherInfo, RT: 0.9}, nil
		}
	}
	return VideoTaskMeta{}, fmt.Errorf("video task meta not found in card HTML for objectId=%q", targetObjectID)
}

func parseAttachmentJSON(raw, targetObjectID string) (VideoTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return VideoTaskMeta{}, err
	}
	defaults, _ := obj["defaults"].(map[string]interface{})
	base := VideoTaskMeta{PUID: jsonStr(defaults, "userid"), RT: 0.9}
	base.FID, _ = strconv.Atoi(jsonStr(defaults, "fid"))
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
		typeStr := firstNonEmptyString(jsonStr(m, "type"), jsonStr(property, "module"))
		if typeStr != "" && typeStr != "video" && typeStr != "insertvideo" && typeStr != "audio" && typeStr != "insertaudio" {
			continue
		}
		objectID := firstNonEmptyString(jsonStr(m, "objectid", "objectId"), jsonStr(property, "objectid", "objectId"))
		if targetObjectID != "" && objectID != "" && objectID != targetObjectID {
			continue
		}
		if objectID == "" {
			objectID = targetObjectID
		}
		jobID := firstNonEmptyString(jsonStr(m, "jobid", "_jobid"), jsonStr(property, "jobid", "_jobid"))
		otherInfo := jsonStr(m, "otherInfo", "otherinfo")
		if i := strings.IndexByte(otherInfo, '&'); i >= 0 {
			otherInfo = otherInfo[:i]
		}
		if jobID == "" {
			continue
		}

		meta := base
		meta.JobID = jobID
		meta.ObjectID = objectID
		meta.OtherInfo = otherInfo
		meta.Title = firstNonEmptyString(jsonStr(property, "name"), jsonStr(property, "title"))
		meta.Mid = firstNonEmptyString(jsonStr(m, "mid"), jsonStr(property, "mid"))
		meta.AttDurationEnc = jsonStr(m, "attDurationEnc")
		meta.VideoFaceCaptureEnc = jsonStr(m, "videoFaceCaptureEnc")
		meta.RandomCaptureTime = jsonStr(m, "randomCaptureTime")
		if playTimeMS, ok := mediaNumber(m, "playTime"); ok && playTimeMS > 0 {
			meta.PlayTime = int(playTimeMS) / 1000
		}
		if duration, ok := mediaNumber(m, "attDuration"); ok && duration > 0 {
			meta.AttDuration = int(duration)
		}
		if rt, ok := mediaNumber(property, "rt"); ok && rt > 0 {
			meta.RT = rt
		}
		if passed, ok := mediaBool(m, "isPassed"); ok {
			meta.IsPassed = passed
			meta.IsPassedKnown = true
		}
		if isJob, ok := mediaBool(m, "job"); ok {
			meta.IsJob = isJob
			meta.IsJobKnown = true
		} else if isJob, ok := mediaBool(property, "isJob"); ok {
			meta.IsJob = isJob
			meta.IsJobKnown = true
		}
		return meta, nil
	}
	return VideoTaskMeta{}, fmt.Errorf("not found")
}

func mediaBool(m map[string]interface{}, key string) (bool, bool) {
	v, ok := m[key].(bool)
	return v, ok
}

func mediaNumber(m map[string]interface{}, key string) (float64, bool) {
	switch v := m[key].(type) {
	case float64:
		return v, true
	case string:
		n, err := strconv.ParseFloat(v, 64)
		return n, err == nil
	case json.Number:
		n, err := v.Float64()
		return n, err == nil
	default:
		return 0, false
	}
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
// fid comes from AttachmentSetting.defaults.fid (the school/fid value), not clazzId.
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
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
	req.Header.Add("Referer", "https://mooc1-api.chaoxing.com/ananas/modules/video/index_wap.html?v=372024-1121-1947")
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.FetchVideoDtoApi(objectID, fid, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	if resp.StatusCode != http.StatusOK {
		return c.FetchVideoDtoApi(objectID, fid, retry-1, fmt.Errorf("video dto status code: %d", resp.StatusCode))
	}
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

type VideoSubmitParams struct {
	ClassID             string
	UserID              string
	JobID               string
	ObjectID            string
	CourseID            string
	CPI                 string
	DToken              string
	OtherInfo           string
	VideoFaceCaptureEnc string
	AttDurationEnc      string
	PlayingTime         int
	Duration            int
	IsDrag              int
	RT                  float64
	NowMillis           int64
}

func VideoSubmitStudyTimeURL(p VideoSubmitParams) (string, string) {
	if p.RT <= 0 {
		p.RT = 0.9
	}
	if p.NowMillis == 0 {
		p.NowMillis = time.Now().UnixMilli()
	}
	clipTime := fmt.Sprintf("0_%d", p.Duration)
	hash := md5.Sum([]byte(fmt.Sprintf("[%s][%s][%s][%s][%d][%s][%d][%s]",
		p.ClassID, p.UserID, p.JobID, p.ObjectID, p.PlayingTime*1000, "d_yHJ!$pdA~5", p.Duration*1000, clipTime)))
	enc := hex.EncodeToString(hash[:])
	params := url.Values{}
	params.Set("clazzId", p.ClassID)
	params.Set("playingTime", strconv.Itoa(p.PlayingTime))
	params.Set("duration", strconv.Itoa(p.Duration))
	params.Set("clipTime", clipTime)
	params.Set("objectId", p.ObjectID)
	params.Set("otherInfo", p.OtherInfo)
	params.Set("courseId", p.CourseID)
	params.Set("jobid", p.JobID)
	params.Set("userid", p.UserID)
	params.Set("isdrag", strconv.Itoa(p.IsDrag))
	params.Set("view", "json")
	params.Set("enc", enc)
	params.Set("rt", strconv.FormatFloat(p.RT, 'f', 2, 64))
	params.Set("videoFaceCaptureEnc", p.VideoFaceCaptureEnc)
	params.Set("dtype", "Video")
	params.Set("_t", strconv.FormatInt(p.NowMillis, 10))
	params.Set("attDuration", strconv.Itoa(p.Duration))
	params.Set("attDurationEnc", p.AttDurationEnc)
	return "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/" + url.PathEscape(p.CPI) + "/" + url.PathEscape(p.DToken) + "?" + params.Encode(), enc
}

// VideoSubmitStudyTimeApi keeps the original call shape for API compatibility.
func (c *XxtClient) VideoSubmitStudyTimeApi(
	classID, userID, jobID, objectID, courseID, cpi, dtoken, otherInfo string,
	playingTime, duration, isdrag int, retry int, lastErr error,
) (string, error) {
	return c.VideoSubmitStudyTimeWithMetaApi(VideoSubmitParams{
		ClassID: classID, UserID: userID, JobID: jobID, ObjectID: objectID, CourseID: courseID,
		CPI: cpi, DToken: dtoken, OtherInfo: otherInfo, PlayingTime: playingTime, Duration: duration,
		IsDrag: isdrag, RT: 0.9,
	}, retry, lastErr)
}

func addVideoSubmitCookies(req *http.Request, c *XxtClient) {
	addCookies(req, c)
	// The console/core adds these browser-side media cookies before every video heartbeat.
	// Keep a session-provided value when present; otherwise supply the same defaults.
	for _, ck := range []http.Cookie{
		{Name: "fanyamoocs", Value: "11401F839C536D9E"},
		{Name: "thirdRegist", Value: "0"},
		{Name: "videojs_id", Value: "1778753"},
	} {
		if c.cookieValue(ck.Name) == "" {
			req.AddCookie(&ck)
		}
	}
}

// VideoSubmitStudyTimeWithMetaApi submits one heartbeat with the card's anti-cheat metadata.
func (c *XxtClient) VideoSubmitStudyTimeWithMetaApi(p VideoSubmitParams, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr, _ := VideoSubmitStudyTimeURL(p)
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.VideoSubmitStudyTimeWithMetaApi(p, retry-1, err)
	}
	addVideoSubmitCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "mooc1.chaoxing.com")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/json")
	req.Header.Add("Sec-Ch-Ua-Platform", `"Windows"`)
	req.Header.Add("Sec-Fetch-Site", "same-origin")
	req.Header.Add("Sec-Fetch-Mode", "cors")
	req.Header.Add("Sec-Fetch-Dest", "empty")
	req.Header.Add("Pragma", "no-cache")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.VideoSubmitStudyTimeWithMetaApi(p, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.VideoSubmitStudyTimeWithMetaApi(p, retry-1, err)
	}
	if resp.StatusCode != http.StatusOK {
		return c.VideoSubmitStudyTimeWithMetaApi(p, retry-1, fmt.Errorf("video submit status code: %d", resp.StatusCode))
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
	if resp.StatusCode != http.StatusOK {
		return c.AudioSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, otherInfo, playingTime, duration, isdrag, retry-1, fmt.Errorf("audio submit status code: %d", resp.StatusCode))
	}
	return string(body), nil
}
