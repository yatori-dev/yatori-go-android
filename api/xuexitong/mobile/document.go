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

type DocumentTaskMeta struct {
	JobID        string `json:"jobId"`
	ObjectID     string `json:"objectId,omitempty"`
	JToken       string `json:"jtoken"`
	Title        string `json:"title,omitempty"`
	DocumentType string `json:"documentType,omitempty"`
	IsJob        bool   `json:"isJob"`
}

type DocumentCompleteParams struct {
	JobID        string
	KnowledgeID  string
	CourseID     string
	ClassID      string
	JToken       string
	DocumentType string
	NowMillis    int64
}

func ParseCardHTMLForDocumentTask(html, targetJobID, targetObjectID, documentType string) (DocumentTaskMeta, error) {
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	if m := reAttach.FindStringSubmatch(html); len(m) > 1 {
		if meta, err := parseDocumentAttachmentJSON(m[1], targetJobID, targetObjectID, documentType); err == nil {
			return meta, nil
		}
	}
	return DocumentTaskMeta{}, fmt.Errorf("document task meta not found")
}

func parseDocumentAttachmentJSON(raw, targetJobID, targetObjectID, documentType string) (DocumentTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return DocumentTaskMeta{}, err
	}
	attachments, _ := obj["attachments"].([]interface{})
	for _, a := range attachments {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		property, _ := m["property"].(map[string]interface{})
		typeStr := firstNonEmptyString(jsonStr(m, "type"), jsonStr(property, "module"))
		if !documentTypeMatches(documentType, typeStr) {
			continue
		}
		jobID := firstNonEmptyString(jsonStr(m, "jobid", "jobId", "_jobid"), jsonStr(property, "jobid", "jobId", "_jobid"))
		objectID := firstNonEmptyString(jsonStr(m, "objectid", "objectId"), jsonStr(property, "objectid", "objectId"))
		if targetJobID != "" && jobID != "" && jobID != targetJobID {
			continue
		}
		if targetObjectID != "" && objectID != "" && objectID != targetObjectID {
			continue
		}
		jtoken := jsonStr(m, "jtoken", "jToken")
		if jobID != "" && jtoken != "" {
			return DocumentTaskMeta{
				JobID:        jobID,
				ObjectID:     objectID,
				JToken:       jtoken,
				Title:        firstNonEmptyString(jsonStr(property, "title"), jsonStr(property, "name"), jsonStr(property, "bookname")),
				DocumentType: normalizeDocumentType(firstNonEmptyString(documentType, typeStr)),
				IsJob:        jsonBool(m, "job"),
			}, nil
		}
	}
	return DocumentTaskMeta{}, fmt.Errorf("not found")
}

func DocumentCompleteURL(p DocumentCompleteParams) string {
	if p.NowMillis == 0 {
		p.NowMillis = time.Now().UnixMilli()
	}
	q := url.Values{}
	q.Set("jobid", p.JobID)
	q.Set("knowledgeid", p.KnowledgeID)
	q.Set("courseid", p.CourseID)
	q.Set("clazzid", p.ClassID)
	q.Set("jtoken", p.JToken)
	q.Set("_dc", strconv.FormatInt(p.NowMillis, 10))
	switch normalizeDocumentType(p.DocumentType) {
	case "insertbook":
		return "https://mooc1.chaoxing.com/ananas/job?" + q.Encode()
	case "insertreadV2":
		q.Set("checkMicroTopic", "true")
		q.Set("microTopicId", "0")
		return "https://mooc1-api.chaoxing.com/ananas/job/readv2?" + q.Encode()
	default:
		return "https://mooc1.chaoxing.com/ananas/job/document?" + q.Encode()
	}
}

func (c *XxtClient) DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr := DocumentCompleteURL(DocumentCompleteParams{
		JobID: jobID, KnowledgeID: knowledgeID, CourseID: courseID, ClassID: classID, JToken: jtoken, DocumentType: documentType,
	})
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/json")
	if normalizeDocumentType(documentType) == "insertreadV2" {
		req.Header.Add("Host", "mooc1-api.chaoxing.com")
		req.Header.Add("X-Requested-With", "XMLHttpRequest")
		req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
	} else {
		req.Header.Add("Host", "mooc1.chaoxing.com")
	}
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	if resp.StatusCode != http.StatusOK {
		return c.DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType, retry-1, fmt.Errorf("status code: %d", resp.StatusCode))
	}
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType, retry-1, err)
	}
	return string(body), nil
}

func normalizeDocumentType(t string) string {
	switch t {
	case "insertbook", "book":
		return "insertbook"
	case "insertreadV2", "insertreadv2", "readv2", "read":
		return "insertreadV2"
	default:
		return "document"
	}
}

func documentTypeMatches(want, got string) bool {
	if want == "" || got == "" {
		return true
	}
	return normalizeDocumentType(want) == normalizeDocumentType(got)
}

func jsonBool(m map[string]interface{}, key string) bool {
	if v, ok := m[key].(bool); ok {
		return v
	}
	return false
}
