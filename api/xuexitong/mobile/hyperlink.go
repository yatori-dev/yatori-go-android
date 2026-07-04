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

type HyperlinkTaskMeta struct {
	JobID    string `json:"jobId"`
	ObjectID string `json:"objectId,omitempty"`
	JToken   string `json:"jtoken"`
	Title    string `json:"title,omitempty"`
}

type HyperlinkCompleteParams struct {
	JobID       string
	KnowledgeID string
	CourseID    string
	ClassID     string
	JToken      string
	NowMillis   int64
}

func ParseCardHTMLForHyperlinkTask(html, targetJobID, targetObjectID string) (HyperlinkTaskMeta, error) {
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	if m := reAttach.FindStringSubmatch(html); len(m) > 1 {
		if meta, err := parseHyperlinkAttachmentJSON(m[1], targetJobID, targetObjectID); err == nil {
			return meta, nil
		}
	}
	return HyperlinkTaskMeta{}, fmt.Errorf("hyperlink task meta not found")
}

func parseHyperlinkAttachmentJSON(raw, targetJobID, targetObjectID string) (HyperlinkTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return HyperlinkTaskMeta{}, err
	}
	attachments, _ := obj["attachments"].([]interface{})
	for _, a := range attachments {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		property, _ := m["property"].(map[string]interface{})
		jobID := firstNonEmptyString(jsonStr(m, "jobid", "jobId"), jsonStr(property, "jobid", "jobId"))
		objectID := firstNonEmptyString(jsonStr(m, "objectid", "objectId"), jsonStr(property, "objectid", "objectId"))
		if targetJobID != "" && jobID != "" && jobID != targetJobID {
			continue
		}
		if targetObjectID != "" && objectID != "" && objectID != targetObjectID {
			continue
		}
		jtoken := jsonStr(m, "jtoken", "jToken")
		if jobID != "" && jtoken != "" {
			return HyperlinkTaskMeta{
				JobID:    jobID,
				ObjectID: objectID,
				JToken:   jtoken,
				Title:    jsonStr(property, "title", "name"),
			}, nil
		}
	}
	return HyperlinkTaskMeta{}, fmt.Errorf("not found")
}

func HyperlinkCompleteURL(p HyperlinkCompleteParams) string {
	if p.NowMillis == 0 {
		p.NowMillis = time.Now().UnixMilli()
	}
	q := url.Values{}
	q.Set("jobid", p.JobID)
	q.Set("knowledgeid", p.KnowledgeID)
	q.Set("courseid", p.CourseID)
	q.Set("clazzid", p.ClassID)
	q.Set("jtoken", p.JToken)
	q.Set("checkMicroTopic", "true")
	q.Set("microTopicId", "undefined")
	q.Set("_dc", strconv.FormatInt(p.NowMillis, 10))
	return "https://mooc1.chaoxing.com/ananas/job/hyperlink?" + q.Encode()
}

func (c *XxtClient) HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	req, err := http.NewRequest("GET", HyperlinkCompleteURL(HyperlinkCompleteParams{
		JobID: jobID, KnowledgeID: knowledgeID, CourseID: courseID, ClassID: classID, JToken: jtoken,
	}), nil)
	if err != nil {
		return c.HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "mooc1.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	if resp.StatusCode != http.StatusOK {
		return c.HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, retry-1, fmt.Errorf("status code: %d", resp.StatusCode))
	}
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, retry-1, err)
	}
	return string(body), nil
}

func firstNonEmptyString(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
