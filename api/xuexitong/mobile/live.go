package mobile

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

type LiveTaskMeta struct {
	JobID         string  `json:"jobId"`
	LiveID        string  `json:"liveId"`
	UserID        string  `json:"userId"`
	StreamName    string  `json:"streamName"`
	VdoID         string  `json:"vdoid"`
	AID           string  `json:"aid"`
	Title         string  `json:"title,omitempty"`
	LiveStatusStr string  `json:"liveStatus,omitempty"`
	Live          bool    `json:"live"`
	IsJob         bool    `json:"isJob"`
	Percent       float64 `json:"percent,omitempty"`
	Duration      int     `json:"duration,omitempty"`
	StatusCode    int     `json:"statusCode,omitempty"`
}

type LiveInfoParams struct {
	LiveID      string
	UserID      string
	ClassID     string
	KnowledgeID string
	CourseID    string
	JobID       string
}

type LiveRelationParams struct {
	CourseID    string
	KnowledgeID string
	JobID       string
	AID         string
}

type LiveSaveTimeParams struct {
	StreamName string
	VdoID      string
	UserID     string
	CourseID   string
	NowMillis  int64
}

type LiveInfoMeta struct {
	Status     bool
	Percent    float64
	Duration   int
	StatusCode int
	Raw        string
}

func ParseCardHTMLForLiveTask(html, targetJobID string) (LiveTaskMeta, error) {
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	if m := reAttach.FindStringSubmatch(html); len(m) > 1 {
		if meta, err := parseLiveAttachmentJSON(m[1], targetJobID); err == nil {
			return meta, nil
		}
	}
	return LiveTaskMeta{}, fmt.Errorf("live task meta not found")
}

func parseLiveAttachmentJSON(raw, targetJobID string) (LiveTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return LiveTaskMeta{}, err
	}
	attachments, _ := obj["attachments"].([]interface{})
	for _, a := range attachments {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		property, _ := m["property"].(map[string]interface{})
		typeStr := firstNonEmptyString(jsonStr(m, "type"), jsonStr(property, "module"))
		if typeStr != "" && typeStr != "live" && typeStr != "insertlive" {
			continue
		}
		jobID := firstNonEmptyString(jsonStr(m, "jobid", "jobId", "_jobid"), jsonStr(property, "jobid", "jobId", "_jobid"))
		if targetJobID != "" && jobID != "" && jobID != targetJobID {
			continue
		}
		liveID := firstNonEmptyString(jsonStr(property, "liveId", "liveid"), jsonStr(m, "liveId", "liveid"))
		streamName := firstNonEmptyString(jsonStr(property, "streamName"), jsonStr(m, "streamName"))
		vdoID := firstNonEmptyString(jsonStr(property, "vdoid", "vdoId"), jsonStr(m, "vdoid", "vdoId"))
		userID := firstNonEmptyString(jsonStr(property, "userId", "userid"), jsonStr(m, "userId", "userid"))
		aid := jsonStr(m, "aid")
		if jobID != "" && liveID != "" && streamName != "" && vdoID != "" {
			return LiveTaskMeta{
				JobID: jobID, LiveID: liveID, UserID: userID, StreamName: streamName, VdoID: vdoID, AID: aid,
				Title: jsonStr(property, "title", "name"), LiveStatusStr: jsonStr(property, "liveStatus"),
				Live: jsonBool(property, "live"), IsJob: jsonBool(m, "job"),
			}, nil
		}
	}
	return LiveTaskMeta{}, fmt.Errorf("not found")
}

func LiveInfoURL(p LiveInfoParams) string {
	q := url.Values{}
	q.Set("liveid", p.LiveID)
	q.Set("userid", p.UserID)
	q.Set("clazzid", p.ClassID)
	q.Set("knowledgeid", p.KnowledgeID)
	q.Set("courseid", p.CourseID)
	q.Set("jobid", p.JobID)
	q.Set("ut", "s")
	return "https://mooc1.chaoxing.com/ananas/live/liveinfo?" + q.Encode()
}

func LiveRelationURL(p LiveRelationParams) string {
	q := url.Values{}
	q.Set("courseid", p.CourseID)
	q.Set("knowledgeid", p.KnowledgeID)
	q.Set("ut", "s")
	q.Set("jobid", p.JobID)
	q.Set("aid", p.AID)
	return "https://mooc1.chaoxing.com/mooc-ans/live/relation?" + q.Encode()
}

func LiveSaveTimeURL(p LiveSaveTimeParams) string {
	if p.NowMillis == 0 {
		p.NowMillis = time.Now().UnixMilli()
	}
	q := url.Values{}
	q.Set("streamName", p.StreamName)
	q.Set("vdoid", p.VdoID)
	q.Set("userId", p.UserID)
	q.Set("isStart", "1")
	q.Set("t", strconv.FormatInt(p.NowMillis, 10))
	q.Set("courseId", p.CourseID)
	return "https://zhibo.chaoxing.com/saveTimePc?" + q.Encode()
}

func ParseLiveInfo(raw string) (LiveInfoMeta, error) {
	var resp struct {
		Status bool `json:"status"`
		Temp   struct {
			Data struct {
				PercentValue float64 `json:"percentValue"`
				Duration     float64 `json:"duration"`
				LiveStatus   float64 `json:"liveStatus"`
			} `json:"data"`
		} `json:"temp"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return LiveInfoMeta{}, err
	}
	return LiveInfoMeta{
		Status: resp.Status, Percent: resp.Temp.Data.PercentValue,
		Duration: int(resp.Temp.Data.Duration), StatusCode: int(resp.Temp.Data.LiveStatus), Raw: raw,
	}, nil
}

func (c *XxtClient) PullLiveInfoApi(liveID, userID, classID, knowledgeID, courseID, jobID string, retry int, lastErr error) (string, error) {
	return c.liveGet(LiveInfoURL(LiveInfoParams{LiveID: liveID, UserID: userID, ClassID: classID, KnowledgeID: knowledgeID, CourseID: courseID, JobID: jobID}), "mooc1.chaoxing.com", retry, lastErr)
}

func (c *XxtClient) LiveRelationApi(courseID, knowledgeID, jobID, aid string, retry int, lastErr error) (string, error) {
	return c.liveGet(LiveRelationURL(LiveRelationParams{CourseID: courseID, KnowledgeID: knowledgeID, JobID: jobID, AID: aid}), "mooc1.chaoxing.com", retry, lastErr)
}

func (c *XxtClient) LiveSaveTimePcApi(streamName, vdoID, userID, courseID string, retry int, lastErr error) (string, error) {
	return c.liveGet(LiveSaveTimeURL(LiveSaveTimeParams{StreamName: streamName, VdoID: vdoID, UserID: userID, CourseID: courseID}), "zhibo.chaoxing.com", retry, lastErr)
}

func (c *XxtClient) liveGet(urlStr, host string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.liveGet(urlStr, host, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", host)
	req.Header.Add("Connection", "keep-alive")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.liveGet(urlStr, host, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	if resp.StatusCode != http.StatusOK {
		return c.liveGet(urlStr, host, retry-1, fmt.Errorf("status code: %d", resp.StatusCode))
	}
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.liveGet(urlStr, host, retry-1, err)
	}
	return string(body), nil
}
