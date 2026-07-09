package mobile

import (
	"crypto/md5"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
	"github.com/google/uuid"
)

type BbsTaskMeta struct {
	JobID          string `json:"jobId"`
	Mid            string `json:"mid"`
	Title          string `json:"title,omitempty"`
	Detail         string `json:"detail,omitempty"`
	Module         string `json:"module,omitempty"`
	OtherInfo      string `json:"otherInfo,omitempty"`
	Enc            string `json:"enc,omitempty"`
	AuthEnc        string `json:"authEnc,omitempty"`
	AllowViewReply int    `json:"allowViewReply,omitempty"`
	ReplyTimes     string `json:"replyTimes,omitempty"`
	ReplyWordNum   string `json:"replyWordNum,omitempty"`
	EndTime        string `json:"endTime,omitempty"`
	IsJob          bool   `json:"isJob"`
}

type BbsTopic struct {
	Platform    string `json:"platform"`
	GroupID     string `json:"groupId,omitempty"`
	BbsID       string `json:"bbsId,omitempty"`
	TopicID     string `json:"topicId,omitempty"`
	TopicUUID   string `json:"topicUUID,omitempty"`
	ClassID     string `json:"classId,omitempty"`
	CourseID    string `json:"courseId,omitempty"`
	ClassChatID string `json:"classChatId,omitempty"`
	Role        string `json:"role,omitempty"`
	Title       string `json:"title,omitempty"`
	Content     string `json:"content,omitempty"`
	URLToken    string `json:"urlToken,omitempty"`
}

type BbsSignatureParams struct {
	C0        string
	Token     string
	NowMillis string
	PUID      string
	UUID      string
	Tag       string
	MaxW      string
	TopicUUID string
	Anonymous string
}

func ParseCardHTMLForBbsTask(html, targetJobID string) (BbsTaskMeta, error) {
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	if m := reAttach.FindStringSubmatch(html); len(m) > 1 {
		if meta, err := parseBbsAttachmentJSON(m[1], targetJobID); err == nil {
			return meta, nil
		}
	}
	return BbsTaskMeta{}, fmt.Errorf("bbs task meta not found")
}

func parseBbsAttachmentJSON(raw, targetJobID string) (BbsTaskMeta, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return BbsTaskMeta{}, err
	}
	attachments, _ := obj["attachments"].([]interface{})
	for _, a := range attachments {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		property, _ := m["property"].(map[string]interface{})
		typeStr := firstNonEmptyString(jsonStr(m, "type"), jsonStr(property, "module"))
		if typeStr != "" && typeStr != "bbs" && typeStr != "insertbbs" {
			continue
		}
		jobID := firstNonEmptyString(jsonStr(m, "jobid", "jobId", "_jobid"), jsonStr(property, "jobid", "jobId", "_jobid"))
		if targetJobID != "" && jobID != "" && jobID != targetJobID {
			continue
		}
		mid := firstNonEmptyString(jsonStr(m, "mid", "mtopid", "mtopicid"), jsonStr(property, "mid", "mtopid", "mtopicid"))
		if jobID != "" && mid != "" {
			return BbsTaskMeta{
				JobID: jobID, Mid: mid,
				Title:          jsonStr(property, "title", "name"),
				Detail:         jsonStr(property, "detail"),
				Module:         firstNonEmptyString(jsonStr(property, "module"), typeStr),
				OtherInfo:      jsonStr(m, "otherInfo", "otherinfo"),
				Enc:            firstNonEmptyString(jsonStr(m, "enc"), jsonStr(obj, "enc")),
				AuthEnc:        jsonStr(m, "authEnc"),
				AllowViewReply: int(jsonFloat(property, "allowViewReply")),
				ReplyTimes:     jsonStr(property, "replytimes"),
				ReplyWordNum:   jsonStr(property, "replywordnum"),
				EndTime:        jsonStr(property, "endtime"),
				IsJob:          jsonBool(m, "job") || jsonBool(property, "isJob"),
			}, nil
		}
	}
	return BbsTaskMeta{}, fmt.Errorf("not found")
}

func PhoneBbsInfoURL(mid, jobID, knowledgeID, courseID, classID string) string {
	return "https://mooc1-api.chaoxing.com/mooc-ans/bbscircle/chapter?mtopicid=" + url.QueryEscape(mid) +
		"&jobid=" + url.QueryEscape(jobID) +
		"&isPortal=false&knowledgeid=" + url.QueryEscape(knowledgeID) +
		"&ut=s&clazzId=" + url.QueryEscape(classID) +
		"&enc&utenc=undefined&courseid=" + url.QueryEscape(courseID) +
		"&isJob=true&isMobile=true"
}

func WebBbsStudyURL(courseID, classID, knowledgeID, enc string) string {
	q := url.Values{}
	q.Set("chapterId", knowledgeID)
	q.Set("courseId", courseID)
	q.Set("clazzid", classID)
	q.Set("enc", enc)
	return "https://mooc1.chaoxing.com/mooc-ans/mycourse/studentstudy?" + q.Encode()
}

func WebBbsCircleURL(mid, jobID, knowledgeID, classID, enc, utEnc, courseID string, isJob bool) string {
	q := url.Values{}
	q.Set("mtopicid", mid)
	q.Set("jobid", jobID)
	q.Set("isPortal", "false")
	q.Set("knowledgeid", knowledgeID)
	q.Set("ut", "s")
	q.Set("clazzId", classID)
	q.Set("enc", enc)
	q.Set("utenc", utEnc)
	q.Set("courseid", courseID)
	q.Set("isJob", fmt.Sprintf("%t", isJob))
	return "https://mooc1.chaoxing.com/mooc-ans/bbscircle/chapter?" + q.Encode()
}

func WebBbsInfoURL(id1, id2, courseID, classID string) string {
	q := url.Values{}
	q.Set("courseId", courseID)
	q.Set("classId", classID)
	return "https://groupweb.chaoxing.com/course/topic/v3/bbs/" + url.PathEscape(id1) + "/" + url.PathEscape(id2) + "/replysList?" + q.Encode()
}

func WebBbsAnswerURL(topicUUID string) string {
	return "https://groupweb.chaoxing.com/pc/invitation/" + url.PathEscape(topicUUID) + "/addReplys"
}

func PhoneBbsDetailURL(c0, nowMillis string) string {
	if c0 == "" {
		c0 = strings.ReplaceAll(uuid.NewString(), "-", "")
	}
	if nowMillis == "" {
		nowMillis = fmt.Sprintf("%d", time.Now().UnixMilli())
	}
	infEnc := InfEncSign(map[string]string{
		"_c_0_": c0,
		"token": "4faa8662c59590c6f43ae9fe5b002b42",
		"_time": nowMillis,
	}, []string{"_c_0_", "token", "_time"})
	q := url.Values{}
	q.Set("_c_0_", c0)
	q.Set("token", "4faa8662c59590c6f43ae9fe5b002b42")
	q.Set("_time", nowMillis)
	q.Set("inf_enc", infEnc)
	return "https://groupyd.chaoxing.com/apis/topic/getTopic?" + q.Encode()
}

func PhoneBbsAnswerURL(p BbsSignatureParams) string {
	if p.C0 == "" {
		p.C0 = strings.ReplaceAll(uuid.NewString(), "-", "")
	}
	if p.Token == "" {
		p.Token = "4faa8662c59590c6f43ae9fe5b002b42"
	}
	if p.NowMillis == "" {
		p.NowMillis = fmt.Sprintf("%d", time.Now().UnixMilli())
	}
	if p.UUID == "" {
		p.UUID = uuid.NewString()
	}
	if p.MaxW == "" {
		p.MaxW = "1080"
	}
	if p.Anonymous == "" {
		p.Anonymous = "0"
	}
	infEnc := InfEncSign(map[string]string{
		"token": p.Token, "_time": p.NowMillis, "_c_0_": p.C0, "puid": p.PUID, "uuid": p.UUID,
		"tag": p.Tag, "maxW": p.MaxW, "topicUUID": p.TopicUUID, "anonymous": p.Anonymous,
	}, []string{"token", "_time", "_c_0_", "puid", "uuid", "tag", "maxW", "topicUUID", "anonymous"})
	q := url.Values{}
	q.Set("token", p.Token)
	q.Set("_time", p.NowMillis)
	q.Set("_c_0_", p.C0)
	q.Set("puid", p.PUID)
	q.Set("uuid", p.UUID)
	q.Set("tag", p.Tag)
	q.Set("maxW", p.MaxW)
	q.Set("topicUUID", p.TopicUUID)
	q.Set("anonymous", p.Anonymous)
	q.Set("inf_enc", infEnc)
	return "https://groupyd.chaoxing.com/apis/invitation/addReply?" + q.Encode()
}

func ParsePhoneBbsTopic(infoHTML, detailJSON string) (BbsTopic, error) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(infoHTML))
	if err != nil {
		return BbsTopic{}, err
	}
	topic := BbsTopic{Platform: "phone"}
	topic.GroupID, _ = doc.Find("input#groupId").Attr("value")
	topic.BbsID, _ = doc.Find("input#bbsId").Attr("value")
	topic.TopicID, _ = doc.Find("input#topicId").Attr("value")
	re := regexp.MustCompile(`classId:\s*"([^"]+)"|courseId:\s*"([^"]+)"|classChatId:\s*"([^"]+)"|role:\s*"([^"]+)"`)
	for _, m := range re.FindAllStringSubmatch(infoHTML, -1) {
		if m[1] != "" {
			topic.ClassID = m[1]
		}
		if m[2] != "" {
			topic.CourseID = m[2]
		}
		if m[3] != "" {
			topic.ClassChatID = m[3]
		}
		if m[4] != "" {
			topic.Role = m[4]
		}
	}
	var detail struct {
		Data struct {
			Title       string `json:"title"`
			TextContent string `json:"text_content"`
			UUID        string `json:"uuid"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(detailJSON), &detail); err != nil {
		return BbsTopic{}, err
	}
	topic.Title = detail.Data.Title
	topic.Content = detail.Data.TextContent
	topic.TopicUUID = detail.Data.UUID
	if topic.TopicID == "" || topic.TopicUUID == "" {
		return BbsTopic{}, fmt.Errorf("bbs topic metadata incomplete")
	}
	return topic, nil
}

func ParseBbsSubmit(raw string, platform string) (bool, string) {
	var resp map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return true, ""
	}
	if platform == "phone" {
		if result, ok := resp["result"].(float64); ok {
			return int(result) == 1, jsonStr(resp, "msg", "message")
		}
	}
	if status, ok := resp["status"].(bool); ok {
		return status, jsonStr(resp, "msg", "message")
	}
	return true, ""
}

var ErrBbsCaptcha = errors.New("xuexitong: bbs web triggered captcha")

func ParseBbsUtEnc(raw string) (string, error) {
	if strings.Contains(raw, "请输入验证码") || strings.Contains(raw, "请输入图片中的验证码") || strings.Contains(raw, "kaptcha") {
		return "", ErrBbsCaptcha
	}
	re := regexp.MustCompile(`var\s+utEnc\s*=\s*"([^"]+)"\s*;`)
	if m := re.FindStringSubmatch(raw); len(m) > 1 {
		return m[1], nil
	}
	return "", fmt.Errorf("bbs web utEnc not found")
}

func ParseBbsCircleIds(raw string) (string, string, error) {
	re := regexp.MustCompile(`topic/v3/bbs/([^/]+)/([^/]+)/replysList`)
	if m := re.FindStringSubmatch(raw); len(m) > 2 {
		return m[1], m[2], nil
	}
	return "", "", fmt.Errorf("bbs web circle ids not found")
}

func ParseWebBbsTopic(raw string) (BbsTopic, error) {
	topic := BbsTopic{Platform: "web"}
	re := regexp.MustCompile(`(?s)topic\s*:\s*(\{.+?\})\s*,\s*course\s*:\s*\{`)
	if m := re.FindStringSubmatch(raw); len(m) > 1 {
		var wire struct {
			UUID    string `json:"uuid"`
			BbsID   string `json:"bbsid"`
			Title   string `json:"title"`
			Content string `json:"content"`
			ID      int    `json:"id"`
		}
		if err := json.Unmarshal([]byte(m[1]), &wire); err != nil {
			return BbsTopic{}, err
		}
		topic.TopicUUID = wire.UUID
		topic.BbsID = wire.BbsID
		topic.Title = wire.Title
		topic.Content = wire.Content
		if wire.ID != 0 {
			topic.TopicID = fmt.Sprintf("%d", wire.ID)
		}
	}
	tokenRe := regexp.MustCompile(`urlToken\s*:\s*'([^']+)'`)
	if m := tokenRe.FindStringSubmatch(raw); len(m) > 1 {
		topic.URLToken = m[1]
	}
	if topic.TopicUUID == "" || topic.URLToken == "" {
		return BbsTopic{}, fmt.Errorf("bbs web topic metadata incomplete")
	}
	return topic, nil
}

func (c *XxtClient) PullBbsUtEncApi(courseID, classID, knowledgeID, enc string, retry int, lastErr error) (string, error) {
	raw, err := c.bbsGet(WebBbsStudyURL(courseID, classID, knowledgeID, enc), "mooc1.chaoxing.com", retry, lastErr)
	if err != nil {
		return "", err
	}
	return ParseBbsUtEnc(raw)
}

func (c *XxtClient) PullBbsCircleIdApi(mid, jobID, knowledgeID, classID, enc, utEnc, courseID string, isJob bool, retry int, lastErr error) (string, string, error) {
	raw, err := c.bbsGet(WebBbsCircleURL(mid, jobID, knowledgeID, classID, enc, utEnc, courseID, isJob), "mooc1.chaoxing.com", retry, lastErr)
	if err != nil {
		return "", "", err
	}
	return ParseBbsCircleIds(raw)
}

func (c *XxtClient) PullBbsInfoApi(id1, id2, courseID, classID string, retry int, lastErr error) (string, error) {
	return c.bbsGet(WebBbsInfoURL(id1, id2, courseID, classID), "groupweb.chaoxing.com", retry, lastErr)
}

func (c *XxtClient) AnswerWebBbsApi(topicUUID, courseID, classID, content, urlToken, bbsID string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	form := url.Values{}
	form.Set("courseId", courseID)
	form.Set("classId", classID)
	form.Set("replyId", "-1")
	form.Set("uuid", uuid.NewString())
	form.Set("topic_content", content)
	form.Set("anonymous", "")
	form.Set("urlToken", urlToken)
	form.Set("bbsid", bbsID)
	req, err := http.NewRequest("POST", WebBbsAnswerURL(topicUUID), strings.NewReader(form.Encode()))
	if err != nil {
		return c.AnswerWebBbsApi(topicUUID, courseID, classID, content, urlToken, bbsID, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "groupweb.chaoxing.com")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")
	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return c.AnswerWebBbsApi(topicUUID, courseID, classID, content, urlToken, bbsID, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.AnswerWebBbsApi(topicUUID, courseID, classID, content, urlToken, bbsID, retry-1, err)
	}
	return string(bodyBytes), nil
}

func (c *XxtClient) PullPhoneBbsInfoApi(mid, jobID, knowledgeID, courseID, classID string, retry int, lastErr error) (string, error) {
	return c.bbsGet(PhoneBbsInfoURL(mid, jobID, knowledgeID, courseID, classID), "mooc1-api.chaoxing.com", retry, lastErr)
}

func (c *XxtClient) PullPhoneBbsDetailApi(topicID string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	puid := c.cookieValue("UID")
	form := url.Values{}
	form.Set("puid", puid)
	form.Set("maxW", "1080")
	form.Set("topicId", topicID)
	req, err := http.NewRequest("POST", PhoneBbsDetailURL("", ""), strings.NewReader(form.Encode()))
	if err != nil {
		return c.PullPhoneBbsDetailApi(topicID, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Connection", "Keep-Alive")
	req.Header.Add("Accept-Language", "zh_CN")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "groupyd.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.PullPhoneBbsDetailApi(topicID, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.PullPhoneBbsDetailApi(topicID, retry-1, err)
	}
	return string(body), nil
}

func (c *XxtClient) AnswerPhoneBbsApi(classID, topicUUID, content string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	puid := c.cookieValue("UID")
	body := url.Values{}
	body.Set("content", content)
	req, err := http.NewRequest("POST", PhoneBbsAnswerURL(BbsSignatureParams{
		PUID: puid, Tag: "classId" + classID, TopicUUID: topicUUID,
	}), strings.NewReader(body.Encode()))
	if err != nil {
		return c.AnswerPhoneBbsApi(classID, topicUUID, content, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Connection", "Keep-Alive")
	req.Header.Add("Accept-Language", "zh_CN")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "groupyd.chaoxing.com")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.AnswerPhoneBbsApi(classID, topicUUID, content, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.AnswerPhoneBbsApi(classID, topicUUID, content, retry-1, err)
	}
	return string(bodyBytes), nil
}

func (c *XxtClient) bbsGet(urlStr, host string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return c.bbsGet(urlStr, host, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
	req.Header.Add("Upgrade-Insecure-Requests", "1")
	req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
	req.Header.Add("X-Requested-With", "com.chaoxing.mobile")
	req.Header.Add("Host", host)
	req.Header.Add("Connection", "keep-alive")
	resp, err := (&http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}).Do(req)
	if err != nil {
		return c.bbsGet(urlStr, host, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.bbsGet(urlStr, host, retry-1, err)
	}
	return string(body), nil
}

func InfEncSign(params map[string]string, order []string) string {
	const desKey = "Z(AfY@XS"
	parts := make([]string, 0, len(order))
	for _, key := range order {
		if v, ok := params[key]; ok {
			parts = append(parts, key+"="+url.QueryEscape(v))
		}
	}
	sum := md5.Sum([]byte(strings.Join(parts, "&") + "&DESKey=" + desKey))
	return hex.EncodeToString(sum[:])
}

func (c *XxtClient) cookieValue(name string) string {
	for _, ck := range c.Cookies {
		if ck.Name == name {
			return ck.Value
		}
	}
	return ""
}

func (c *XxtClient) Puid() string {
	return c.cookieValue("UID")
}

func jsonFloat(m map[string]interface{}, key string) float64 {
	if v, ok := m[key].(float64); ok {
		return v
	}
	return 0
}
