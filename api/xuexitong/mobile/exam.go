package mobile

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	mrand "math/rand"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// xxtIMEI is a stable per-process device id used in exam URLs/forms.
var xxtIMEI = randomHex(16)

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		for i := range b {
			b[i] = byte(mrand.Intn(256))
		}
	}
	return hex.EncodeToString(b)
}

// ExamItem is one exam listed for a course.
type ExamItem struct {
	Name       string `json:"name"`
	Status     string `json:"status"`
	RemainTime string `json:"remainTime"`
	TaskRefId  string `json:"taskRefId"`
	CourseId   string `json:"courseId"`
	UserId     string `json:"userId"`
	ClazzId    string `json:"clazzId"`
	Type       string `json:"type"`
	EncTask    string `json:"encTask"`
	MsgId      string `json:"msgId"`
}

// ExamEnterInfo carries what's scraped from the exam-enter / paper-open pages.
type ExamEnterInfo struct {
	QuestionTotal     int    `json:"questionTotal"`
	CaptchaCaptchaId  string `json:"captchaCaptchaId"`
	ExamRelationId    string `json:"examRelationId"` // testPaperId
	AnswerId          string `json:"answerId"`       // testUserRelationId / examAnswerId
	Cpi               string `json:"cpi"`
	Enc               string `json:"enc"`
	EncRemainTime     string `json:"encRemainTime"`
	EncLastUpdateTime string `json:"encLastUpdateTime"`
	RemainTime        string `json:"remainTime"`
}

// ExamSubmitEntity carries the per-question fields needed to submit one answer.
type ExamSubmitEntity struct {
	CourseId           string `json:"courseId"`
	TestPaperId        string `json:"testPaperId"`
	TestUserRelationId string `json:"testUserRelationId"`
	ClassId            string `json:"classId"`
	Cpi                string `json:"cpi"`
	Enc                string `json:"enc"`
	EncRemainTime      string `json:"encRemainTime"`
	EncLastUpdateTime  string `json:"encLastUpdateTime"`
	UserId             string `json:"userId"`
	EnterPageTime      string `json:"enterPageTime"`
	RemainTime         string `json:"remainTime"`
	RemainTimeParam    string `json:"remainTimeParam"`
	Score              string `json:"score"`
	QuestionId         string `json:"questionId"`
	TypeCode           string `json:"typeCode"`
	TypeName           string `json:"typeName"`
	Tid                string `json:"tid"`
	AnswerId           string `json:"answerId"`
}

// ExamQuestion is one parsed exam question for the host to answer.
type ExamQuestion struct {
	Submit   ExamSubmitEntity `json:"submit"`
	Type     string           `json:"type"`
	TypeCode string           `json:"typeCode"`
	Content  string           `json:"content"`
	Options  []string         `json:"options"`
}

// --- HTTP endpoints ---

// ExamListHtmlApi fetches the exam task-list HTML for a course.
func (c *XxtClient) ExamListHtmlApi(courseId, classId, cpi string) (string, error) {
	return c.workHTMLGet("https://mooc1-api.chaoxing.com/mooc-ans/exam/phone/task-list?courseId="+courseId+"&classId="+classId+"&cpi="+cpi, nil)
}

// ExamEnterInformHtmlApi opens an exam, returning the enter HTML and final URL
// (slider referer). Handles 待重做 (redo) and nested examJumpUrl pages.
func (c *XxtClient) ExamEnterInformHtmlApi(taskrefId, msgId, courseId, userId, clazzId, enterType, encTask string) (string, string, error) {
	var finalURL string
	u := "https://mooc1-api.chaoxing.com/exam-ans/android/mtaskmsgspecial?taskrefId=" + taskrefId + "&msgId=" + msgId +
		"&courseId=" + courseId + "&userId=" + userId + "&clazzId=" + clazzId + "&type=" + enterType + "&enc_task=" + encTask
	body, err := c.workHTMLGet(u, &finalURL)
	if err != nil {
		return "", finalURL, err
	}
	if strings.Contains(body, "待重做") {
		redo, rerr := c.workHTMLGet(finalURL+"&redo=1", nil)
		return redo, finalURL, rerr
	}
	if m := regexp.MustCompile(`id="examJumpUrl"\s+value="([^"]+)"`).FindStringSubmatch(body); len(m) >= 2 {
		two, terr := c.workHTMLGet(m[1], nil)
		return two, finalURL, terr
	}
	return body, finalURL, nil
}

// ExamPaperHtmlApi opens the exam paper (threading the slider validate token).
func (c *XxtClient) ExamPaperHtmlApi(courseId, classId, examId, source, examAnswerId, cpi, captchavalidate, jt string) (string, error) {
	u := "https://mooc1-api.chaoxing.com/exam-ans/exam/phone/start?courseId=" + courseId + "&classId=" + classId +
		"&examId=" + examId + "&source=" + source + "&examAnswerId=" + examAnswerId + "&cpi=" + cpi +
		"&keyboardDisplayRequiresUserAction=1&imei=" + xxtIMEI + "&faceDetectionResult&captchavalidate=" + captchavalidate +
		"&jt=" + jt + "&_v=0.3868294515418076&cxcid&cxtime&signt&_signcode=3&_signc=0&_signe=3-1&signk"
	return c.workHTMLGet(u, nil)
}

// ExamQuestionApi pulls one exam question (by index).
func (c *XxtClient) ExamQuestionApi(courseId, classId, tId, id, cpi, remainTimeParam, enc, relationAnswerLastUpdateTime string, index int) (string, error) {
	u := "https://mooc1-api.chaoxing.com/exam-ans/exam/test/reVersionTestStartNew?keyboardDisplayRequiresUserAction=1&courseId=" + courseId +
		"&classId=" + classId + "&source=0&imei=" + xxtIMEI + "&tId=" + tId + "&id=" + id + "&p=1&start=" + strconv.Itoa(index) +
		"&cpi=" + cpi + "&isphone=true&monitorStatus=0&monitorOp=-1&remainTimeParam=" + remainTimeParam +
		"&relationAnswerLastUpdateTime=" + relationAnswerLastUpdateTime + "&enc=" + enc
	return c.workHTMLGet(u, nil)
}

// SubmitExamAnswerApi submits one exam question's answer (signed). tempSave=true
// saves; tempSave=false submits the whole paper.
func (c *XxtClient) SubmitExamAnswerApi(e *ExamSubmitEntity, options, hostAnswers []string, tempSave bool) (string, error) {
	sig := GetExamSignature(e.UserId, e.QuestionId, mrand.Intn(100)+900, mrand.Intn(900)+100)
	u := "https://mooc1-api.chaoxing.com/exam-ans/exam/test/reVersionSubmitTestNew?classId=" + e.ClassId +
		"&courseId=" + e.CourseId + "&testPaperId=" + e.TestPaperId + "&testUserRelationId=" + e.TestUserRelationId +
		"&cpi=" + e.Cpi + "&version=1&tempSave=" + fmt.Sprintf("%v", tempSave) + "&pos=" + sig["pos"].(string) +
		"&rd=" + fmt.Sprintf("%.16f", sig["rd"]) + "&value=" + url.QueryEscape(sig["value"].(string)) +
		"&qid=" + e.QuestionId + "&_edt=" + sig["_edt"].(string) + "&_csign=1&_signcode=3&_signc=0&_signe=3-1&_signk&_cxcid&_cxtime&_signt"

	values := ExamAnswerForm(e, options, hostAnswers, tempSave)
	req, err := http.NewRequest("POST", u, strings.NewReader(values.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("Origin", "https://mooc1-api.chaoxing.com")
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
	req.Header.Add("Referer", examSubmitReferer(e, time.Now().UnixMilli()))
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Add("Connection", "keep-alive")
	addCookies(req, c)
	res, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	return string(body), err
}

func examSubmitReferer(e *ExamSubmitEntity, relationAnswerLastUpdateTime int64) string {
	q := url.Values{}
	q.Set("keyboardDisplayRequiresUserAction", "1")
	q.Set("courseId", e.CourseId)
	q.Set("classId", e.ClassId)
	q.Set("source", "0")
	q.Set("imei", xxtIMEI)
	q.Set("tId", e.Tid)
	q.Set("id", e.AnswerId)
	q.Set("p", "1")
	q.Set("start", "1")
	q.Set("cpi", e.Cpi)
	q.Set("isphone", "true")
	q.Set("monitorStatus", "0")
	q.Set("monitorOp", "-1")
	q.Set("remainTimeParam", e.RemainTimeParam)
	q.Set("relationAnswerLastUpdateTime", strconv.FormatInt(relationAnswerLastUpdateTime, 10))
	q.Set("enc", e.Enc)
	return "https://mooc1-api.chaoxing.com/exam-ans/exam/test/reVersionTestStartNew?" + q.Encode()
}

// ExamAnswerForm builds the exam submit form body (per-type answer encoding).
// Mirrors go-core SubmitExamAnswerApi; fill uses answerEditor{qid}{i}.
func ExamAnswerForm(e *ExamSubmitEntity, options, hostAnswers []string, tempSave bool) url.Values {
	v := url.Values{}
	v.Set("courseId", e.CourseId)
	v.Set("testPaperId", e.TestPaperId)
	v.Set("testUserRelationId", e.TestUserRelationId)
	v.Set("classId", e.ClassId)
	v.Set("type", "0")
	v.Set("isphone", "true")
	v.Set("imei", xxtIMEI)
	v.Set("subCount", "")
	v.Set("remainTime", e.RemainTime)
	v.Set("tempSave", strconv.FormatBool(tempSave))
	v.Set("timeOver", "false")
	v.Set("encRemainTime", e.EncRemainTime)
	v.Set("encLastUpdateTime", e.EncLastUpdateTime)
	v.Set("enc", e.Enc)
	v.Set("userId", e.UserId)
	v.Set("start", "0")
	v.Set("enterPageTime", e.EnterPageTime)
	v.Set("randomOptions", "false")
	v.Add("questionId", e.QuestionId)
	v.Set("monitorforcesubmit", "0")
	v.Set("answeredView", "0")
	v.Set("exitdtime", "0")
	v.Set("paperGroupId", "0")
	qid := e.QuestionId
	v.Set("score"+qid, e.Score)
	switch e.TypeCode {
	case "0":
		v.Set("type"+qid, e.TypeCode)
		v.Set("typeName"+qid, e.TypeName)
		v.Set("hidetext", "")
		ans := ""
		for _, a := range hostAnswers {
			ans += similaritySelect(a, options)
		}
		v.Set("answer"+qid, ans)
	case "1":
		v.Set("type"+qid, e.TypeCode)
		v.Set("typeName"+qid, e.TypeName)
		v.Set("hidetext", "")
		ans := ""
		for _, a := range hostAnswers {
			ans += similaritySelect(a, options)
		}
		v.Set("answers"+qid, ans)
	case "3":
		v.Set("type"+qid, e.TypeCode)
		v.Set("typeName"+qid, e.TypeName)
		ans := ""
		for _, a := range hostAnswers {
			if similaritySelect(a, options) == "A" {
				ans = "true"
			} else {
				ans = "false"
			}
		}
		v.Set("answer"+qid, ans)
	case "2":
		v.Set("type"+qid, e.TypeCode)
		v.Set("typeName"+qid, e.TypeName)
		blankNum := ""
		for i, a := range hostAnswers {
			v.Set("answerEditor"+qid+strconv.Itoa(i+1), a)
			blankNum += strconv.Itoa(i+1) + ","
		}
		v.Set("blankNum"+qid, blankNum)
	case "4", "6":
		v.Set("type"+qid, e.TypeCode)
		v.Set("typeName"+qid, e.TypeName)
		ans := ""
		if len(hostAnswers) > 0 {
			ans = hostAnswers[0]
		}
		v.Set("answer"+qid, ans)
	}
	return v
}

// --- parsers ---

// ParseExamList parses the exam task-list HTML into ExamItem list.
func ParseExamList(html string) ([]ExamItem, error) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("xuexitong: exam list parse error: %w", err)
	}
	items := make([]ExamItem, 0)
	doc.Find("ul.nav li").Each(func(i int, li *goquery.Selection) {
		rawURL, _ := li.Attr("data")
		parsed, _ := url.Parse(rawURL)
		params := map[string]string{}
		if parsed != nil {
			for k, vv := range parsed.Query() {
				if len(vv) > 0 {
					params[k] = vv[0]
				}
			}
		}
		div := li.Find("div")
		name := strings.TrimSpace(div.Find("p").Text())
		spans := div.Find("span")
		status := strings.TrimSpace(spans.Eq(0).Text())
		remain := ""
		if spans.Length() > 1 {
			remain = strings.TrimSpace(spans.Eq(1).Text())
		}
		items = append(items, ExamItem{
			Name: name, Status: status, RemainTime: remain,
			TaskRefId: params["taskrefId"], CourseId: params["courseId"], UserId: params["userId"],
			ClazzId: params["clazzId"], Type: params["type"], EncTask: params["enc_task"], MsgId: params["msgId"],
		})
	})
	return items, nil
}

// ParseExamEnter scrapes the exam-enter HTML for question count, captcha id, and
// the testPaperId/testUserRelationId/cpi needed to open the paper.
func ParseExamEnter(html string) (ExamEnterInfo, error) {
	var info ExamEnterInfo
	if m := regexp.MustCompile(`共\s*(\d+)\s*题`).FindStringSubmatch(html); len(m) > 1 {
		info.QuestionTotal, _ = strconv.Atoi(m[1])
	}
	if info.QuestionTotal == 0 {
		if m := regexp.MustCompile(`共包含\s*(\d+)\s*道题目`).FindStringSubmatch(html); len(m) > 1 {
			info.QuestionTotal, _ = strconv.Atoi(m[1])
		}
	}
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return info, fmt.Errorf("xuexitong: exam enter parse error: %w", err)
	}
	doc.Find("input[type='hidden']").Each(func(i int, sel *goquery.Selection) {
		id, _ := sel.Attr("id")
		value, _ := sel.Attr("value")
		switch id {
		case "captchaCaptchaId":
			info.CaptchaCaptchaId = value
		case "testPaperId":
			info.ExamRelationId = value
		case "testUserRelationId":
			info.AnswerId = value
		case "cpi":
			info.Cpi = value
		}
	})
	return info, nil
}

// ParseExamQuestion parses one exam question HTML into an ExamQuestion. Ported from
// go-core HtmlQuestionTurnEntity (also used for the first question on paper-open).
func ParseExamQuestion(html string) (ExamQuestion, error) {
	html = NormalizeXxtFontHTML(html)
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return ExamQuestion{}, fmt.Errorf("xuexitong: exam question parse error: %w", err)
	}
	questionId, _ := doc.Find("#questionId").Attr("value")
	typeCode, _ := doc.Find(`input[name="type` + questionId + `"]`).Attr("value")
	typeName, _ := doc.Find(`input[name="typeName` + questionId + `"]`).Attr("value")

	var q ExamQuestion
	switch typeCode {
	case "0":
		q.Type, q.Options, q.Content = XxtQTypeSingle, examChoiceOptions(doc, "singleChoice"), examTitle(doc)
	case "1":
		q.Type, q.Options, q.Content = XxtQTypeMulti, examChoiceOptions(doc, "mulChoice"), examTitle(doc)
	case "2":
		q.Type, q.Options, q.Content = XxtQTypeFill, examGrayList(doc), examTitle(doc)
	case "3":
		q.Type, q.Options, q.Content = XxtQTypeJudge, examJudgeOptions(doc), examTitle(doc)
	case "4":
		q.Type, q.Options, q.Content = XxtQTypeShort, examGrayList(doc), examTitle(doc)
	case "6":
		q.Type, q.Options, q.Content = XxtQTypeEssay, examGrayList(doc), examTitle(doc)
	default:
		q.Type, q.Content = XxtQTypeOther, examTitle(doc)
	}
	get := func(id string) string { v, _ := doc.Find("#" + id).Attr("value"); return v }
	getName := func(name string) string { v, _ := doc.Find(`input[name="` + name + `"]`).Attr("value"); return v }
	if qid := examQuestionId(doc); qid != "" {
		questionId = qid
	}
	q.TypeCode = typeCode
	q.Submit = ExamSubmitEntity{
		CourseId:           get("courseId"),
		TestPaperId:        get("testPaperId"),
		TestUserRelationId: get("testUserRelationId"),
		ClassId:            get("classId"),
		Cpi:                get("cpi"),
		Enc:                get("enc"),
		EncRemainTime:      get("encRemainTime"),
		EncLastUpdateTime:  get("encLastUpdateTime"),
		UserId:             get("userId"),
		EnterPageTime:      get("enterPageTime"),
		RemainTime:         get("remainTime"),
		Score:              getName("score" + questionId),
		QuestionId:         questionId,
		TypeCode:           typeCode,
		TypeName:           typeName,
	}
	return q, nil
}

func examQuestionId(doc *goquery.Document) string {
	id := ""
	doc.Find("div.questionWrap").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		if v, ok := sel.Attr("data"); ok && v != "" {
			id = v
			return false
		}
		return true
	})
	return id
}

func examTitle(doc *goquery.Document) string {
	title := ""
	doc.Find("div.questionWrap").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.tit`).Each(func(i int, s *goquery.Selection) {
			title = extractQuestion(s)
		})
		return false
	})
	return title
}

// examChoiceOptions reads single/multi choice options (className "singleChoice" or
// "mulChoice"); each option text is letter + answerInfo cc text.
func examChoiceOptions(doc *goquery.Document, className string) []string {
	var opts []string
	doc.Find("div.questionWrap").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		resOptions := make(map[string]string)
		sel.Find("." + className).Each(func(i int, s *goquery.Selection) {
			letter, _ := s.Attr("name")
			text := strings.TrimSpace(s.Find(`.answerInfo cc`).Text())
			if letter != "" {
				resOptions[letter] = letter + text
			}
		})
		for _, slt := range []string{"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N"} {
			if resOptions[slt] == "" {
				break
			}
			opts = append(opts, resOptions[slt])
		}
		return false
	})
	return opts
}

func examJudgeOptions(doc *goquery.Document) []string {
	var opts []string
	doc.Find("div.questionWrap").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.answerList`).Each(func(i int, s *goquery.Selection) {
			letter := strings.TrimSpace(s.Find(`.No`).Text())
			text := strings.TrimSpace(s.Find(`.answerInfo`).Text())
			opts = append(opts, letter+text)
		})
		return false
	})
	return opts
}

func examGrayList(doc *goquery.Document) []string {
	var opts []string
	doc.Find("div.questionWrap").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.completionList`).Each(func(i int, s *goquery.Selection) {
			opts = append(opts, strings.TrimSpace(s.Find(`.grayTit`).Text()))
		})
		return false
	})
	return opts
}

// --- exam submit signature (ported verbatim from go-core ExamApi GetExamSignature) ---

func GetExamSignature(uid, qid string, x, y int) map[string]interface{} {
	ts := getTs()
	r1 := mrand.Intn(9)
	r2 := mrand.Intn(9)
	a := fmt.Sprintf("%s%s%d%d", randomHex(16), ts[4:], r1, r2)
	if qid != "" {
		a += qid
	}
	var temp int64 = 0
	for _, ch := range a {
		temp = (temp << 5) - temp + int64(ch)
	}
	salt := fmt.Sprintf("%d%d%d", r1, r2, (int64(0x7fffffff)&temp)%10)
	encVal := uid
	if qid != "" {
		encVal += "_" + qid
	}
	encVal += "|" + salt
	var sb strings.Builder
	for _, ch := range encVal {
		sb.WriteString(strconv.Itoa(int(ch)))
	}
	encVal2 := sb.String()
	b := len(encVal2) / 5
	cStr := string(encVal2[b]) + string(encVal2[2*b]) + string(encVal2[3*b]) + string(encVal2[4*b])
	cc, _ := strconv.Atoi(cStr)
	d := len(encVal)/2 + 1
	first10, _ := strconv.Atoi(encVal2[:10])
	e := (int64(cc)*int64(first10) + int64(d)) % 0x7FFFFFFF
	pos := fmt.Sprintf("(%d|%d)", x, y)
	var result strings.Builder
	for _, ch := range pos {
		key := int(math.Floor(float64(e) / float64(0x7FFFFFFF) * 0xFF))
		result.WriteString(fmt.Sprintf("%02x", int(ch)^key))
		e = (int64(cc)*e + int64(d)) % 0x7FFFFFFF
	}
	return map[string]interface{}{
		"pos":   result.String() + randomHex(4),
		"rd":    mrand.Float64(),
		"value": pos,
		"_edt":  ts + salt,
	}
}

func getTs() string { return strconv.FormatInt(time.Now().UnixMilli(), 10) }
