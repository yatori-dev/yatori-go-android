package mobile

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// ChapterTestMeta carries the whole-paper submit context scraped from the paper.
type ChapterTestMeta struct {
	Title            string `json:"title"`
	CourseId         string `json:"courseId"`
	ClassId          string `json:"classId"`
	Knowledgeid      string `json:"knowledgeId"`
	Cpi              string `json:"cpi"`
	JobId            string `json:"jobId"`
	TotalQuestionNum string `json:"totalQuestionNum"`
	AnswerId         string `json:"answerId"`
	WorkAnswerId     string `json:"workAnswerId"`
	Api              string `json:"api"`
	FullScore        string `json:"fullScore"`
	OldSchoolId      string `json:"oldSchoolId"`
	OldWorkId        string `json:"oldWorkId"`
	WorkRelationId   string `json:"workRelationId"`
	EncWork          string `json:"encWork"`
}

// ChapterTestQuestion is one question in a chapter-test paper. Options is a letter
// map for choice/judge; Blanks is the blank count for fill; others use Content only.
type ChapterTestQuestion struct {
	Qid      string            `json:"qid"`
	Type     string            `json:"type"`     // localized Chinese type
	TypeCode string            `json:"typeCode"` // 0/1/2/3/4/5/6/11/8
	Content  string            `json:"content"`
	Options  map[string]string `json:"options"` // letter -> text (choice/judge)
	Selects  []string          `json:"selects"` // matching: right-column items
	Blanks   int               `json:"blanks"`  // fill: number of blanks
}

// ChapterTestAnswer is one host-supplied answer for a chapter-test question.
type ChapterTestAnswer struct {
	Qid      string   `json:"qid"`
	TypeCode string   `json:"typeCode"`
	Options  []string `json:"options"` // ordered option texts (choice/judge) for letter mapping
	Selects  []string `json:"selects"` // matching right-column for letter mapping
	Answers  []string `json:"answers"` // host answer texts
}

type ChapterTestCardMeta struct {
	WorkID   string
	SchoolID string
	JobID    string
	KToken   string
	Enc      string
	IsJob    bool
}

func ParseCardHTMLForChapterTest(html string) (ChapterTestCardMeta, error) {
	reAttach := regexp.MustCompile(`(?i)(?:window\.)?attachmentSetting\s*=\s*(\{[^;]+\})\s*;`)
	m := reAttach.FindStringSubmatch(html)
	if len(m) < 2 {
		return ChapterTestCardMeta{}, fmt.Errorf("xuexitong: chapter-test card attachmentSetting not found")
	}
	var root map[string]interface{}
	if err := json.Unmarshal([]byte(m[1]), &root); err != nil {
		return ChapterTestCardMeta{}, err
	}
	out := ChapterTestCardMeta{}
	if defaults, _ := root["defaults"].(map[string]interface{}); defaults != nil {
		out.KToken = jsonStr(defaults, "ktoken")
	}
	attachments, _ := root["attachments"].([]interface{})
	for _, item := range attachments {
		att, _ := item.(map[string]interface{})
		if att == nil {
			continue
		}
		property, _ := att["property"].(map[string]interface{})
		workID := jsonStr(property, "workid", "workId")
		jobID := jsonStr(property, "_jobid", "jobid", "jobId")
		if workID == "" || jobID == "" {
			continue
		}
		out.WorkID = workID
		out.SchoolID = jsonStr(property, "schoolid", "schoolId")
		if out.SchoolID == "" {
			out.SchoolID = "0"
		}
		out.JobID = jobID
		out.Enc = jsonStr(att, "enc")
		if b, ok := att["job"].(bool); ok {
			out.IsJob = b
		}
		return out, nil
	}
	return ChapterTestCardMeta{}, fmt.Errorf("xuexitong: chapter-test work attachment not found")
}

// --- HTTP endpoints ---

// WorkFetchQuestionApi pulls the whole chapter-test paper HTML.
func (c *XxtClient) WorkFetchQuestionApi(courseId, classId, knowledgeId, cpi, workId, schoolId, jobId, ktoken, enc string) (string, error) {
	params := url.Values{}
	params.Add("courseid", courseId)
	wid := workId
	if schoolId != "" && schoolId != "0" {
		wid = schoolId + "-" + workId
	}
	params.Add("workid", wid)
	params.Add("jobid", jobId)
	params.Add("needRedirect", "true")
	params.Add("knowledgeid", knowledgeId)
	params.Add("userid", c.UserID)
	params.Add("ut", "s")
	params.Add("clazzId", classId)
	params.Add("cpi", cpi)
	params.Add("ktoken", ktoken)
	params.Add("enc", enc)
	return c.workHTMLGet("https://mooc1-api.chaoxing.com/android/mworkspecial?"+params.Encode(), nil)
}

// WorkNewSubmitAnswerApi submits the whole chapter-test paper. isSubmit=true sends
// pyFlag="" (final submit); false sends pyFlag="1" (save). On a character captcha
// the server body contains "请输入验证码" → returns ErrChapterCaptcha.
func (c *XxtClient) WorkNewSubmitAnswerApi(meta *ChapterTestMeta, answers []ChapterTestAnswer, isSubmit bool) (string, error) {
	body, contentType := ChapterTestSubmitForm(c.UserID, meta, answers, isSubmit)
	u := fmt.Sprintf("https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew?_classId=%s&courseid=%s&token=%s&totalQuestionNum=%s&ua=pc&formType=post&saveStatus=1&version=1&tempsave=1",
		meta.ClassId, meta.CourseId, meta.EncWork, meta.TotalQuestionNum)
	req, err := http.NewRequest("POST", u, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Set("Content-Type", contentType)
	addCookies(req, c)
	res, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	respBody, err := io.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	mergeCookies(c, res.Cookies())
	s := string(respBody)
	if strings.Contains(s, "请输入验证码") || strings.Contains(s, "请输入图片中的验证码") {
		return s, ErrChapterCaptcha
	}
	return s, nil
}

// ErrChapterCaptcha signals the chapter-test submit hit a character captcha that
// the host must OCR (Go core cannot solve it without the banned ddddocr).
var ErrChapterCaptcha = errors.New("xuexitong: 触发验证码")

// ChapterCaptchaImageApi fetches the character-captcha image (for the host to OCR).
func (c *XxtClient) ChapterCaptchaImageApi() (image.Image, error) {
	u := "https://mooc1.chaoxing.com/mooc-ans/kaptcha-img/code?" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	addCookies(req, c)
	res, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	img, _, err := image.Decode(res.Body)
	if err != nil {
		return nil, fmt.Errorf("xuexitong: captcha image decode failed: %w", err)
	}
	return img, nil
}

// PassChapterCaptchaApi submits the host's OCR result; returns true on success
// (server 302). code is the OCR'd captcha text.
func (c *XxtClient) PassChapterCaptchaApi(code string) (bool, error) {
	payload := &bytes.Buffer{}
	w := multipart.NewWriter(payload)
	_ = w.WriteField("app", "0")
	_ = w.WriteField("ucode", code)
	if err := w.Close(); err != nil {
		return false, err
	}
	req, err := http.NewRequest("POST", "https://mooc1-api.chaoxing.com/html/processVerify.ac", payload)
	if err != nil {
		return false, err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Set("Content-Type", w.FormDataContentType())
	addCookies(req, c)
	client := &http.Client{
		Transport:     httpTransport(c),
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	res, err := client.Do(req)
	if err != nil {
		return false, err
	}
	defer res.Body.Close()
	mergeCookies(c, res.Cookies())
	return res.StatusCode == 302, nil
}

// ChapterTestSubmitForm builds the multipart body for a whole-paper submit.
// Ported from go-core WorkNewSubmitAnswer. isSubmit=true → pyFlag="" (submit),
// false → pyFlag="1" (save). Returns (body, contentType).
func ChapterTestSubmitForm(userId string, meta *ChapterTestMeta, answers []ChapterTestAnswer, isSubmit bool) ([]byte, string) {
	pyFlag := "1"
	if isSubmit {
		pyFlag = ""
	}
	payload := &bytes.Buffer{}
	w := multipart.NewWriter(payload)
	_ = w.WriteField("pyFlag", pyFlag)
	_ = w.WriteField("courseId", meta.CourseId)
	_ = w.WriteField("classId", meta.ClassId)
	_ = w.WriteField("api", meta.Api)
	_ = w.WriteField("workAnswerId", meta.WorkAnswerId)
	_ = w.WriteField("answerId", meta.AnswerId)
	_ = w.WriteField("totalQuestionNum", meta.TotalQuestionNum)
	_ = w.WriteField("fullScore", meta.FullScore)
	_ = w.WriteField("knowledgeid", meta.Knowledgeid)
	_ = w.WriteField("oldSchoolId", meta.OldSchoolId)
	_ = w.WriteField("oldWorkId", meta.OldWorkId)
	_ = w.WriteField("jobid", meta.JobId)
	_ = w.WriteField("workRelationId", meta.WorkRelationId)
	_ = w.WriteField("enc", "")
	_ = w.WriteField("enc_work", meta.EncWork)
	_ = w.WriteField("userId", userId)
	_ = w.WriteField("cpi", meta.Cpi)
	_ = w.WriteField("workTimesEnc", "")
	_ = w.WriteField("randomOptions", "false")
	_ = w.WriteField("isAccessibleCustomFid", "0")

	answerwqbid := ""
	for _, a := range answers {
		if a.Qid != "" {
			answerwqbid += a.Qid + ","
		}
		switch a.TypeCode {
		case "0": // single
			ans := ""
			for _, item := range a.Answers {
				ans += similaritySelect(item, a.Options)
			}
			_ = w.WriteField("answer"+a.Qid, ans)
			_ = w.WriteField("answertype"+a.Qid, "0")
		case "1": // multiple — sort letters
			ans := ""
			for _, item := range a.Answers {
				ans += similaritySelect(item, a.Options)
			}
			r := []rune(ans)
			sort.Slice(r, func(i, j int) bool { return r[i] < r[j] })
			_ = w.WriteField("answer"+a.Qid, string(r))
			_ = w.WriteField("answertype"+a.Qid, "1")
		case "3": // judge — 正确→true / 错误→false
			ans := ""
			for _, item := range a.Answers {
				switch item {
				case "正确", "对", "true", "√":
					ans = "true"
				case "错误", "错", "false", "×":
					ans = "false"
				default:
					ans = item
				}
			}
			_ = w.WriteField("answer"+a.Qid, ans)
			_ = w.WriteField("answertype"+a.Qid, "3")
		case "2": // fill — answer{qid}{i} + tiankongsize
			for i, v := range a.Answers {
				_ = w.WriteField("answer"+a.Qid+strconv.Itoa(i+1), v)
			}
			_ = w.WriteField("tiankongsize"+a.Qid, strconv.Itoa(len(a.Answers)))
			_ = w.WriteField("answertype"+a.Qid, "2")
		case "4", "5", "6", "8": // short/term/essay/other — single text
			text := ""
			if len(a.Answers) > 0 {
				text = a.Answers[0]
			}
			_ = w.WriteField("answer"+a.Qid, text)
			_ = w.WriteField("answertype"+a.Qid, a.TypeCode)
		case "11": // matching — JSON dept list of right-column letters
			type selData struct {
				Name    int    `json:"name"`
				Content string `json:"content"`
			}
			list := make([]selData, 0, len(a.Answers))
			for i, answer := range a.Answers {
				right := answer
				if parts := strings.SplitN(answer, "->", 2); len(parts) == 2 {
					right = parts[1]
				}
				sel := similaritySelect(right, a.Selects)
				list = append(list, selData{Name: i + 1, Content: sel})
				_ = w.WriteField("dept", sel)
			}
			j, _ := json.Marshal(list)
			_ = w.WriteField("answer"+a.Qid, url.QueryEscape(string(j)))
			_ = w.WriteField("answertype"+a.Qid, "11")
		default:
			text := ""
			if len(a.Answers) > 0 {
				text = a.Answers[0]
			}
			_ = w.WriteField("answer"+a.Qid, text)
			_ = w.WriteField("answertype"+a.Qid, "6")
		}
	}
	_ = w.WriteField("answerwqbid", answerwqbid)
	_ = w.Close()
	return payload.Bytes(), w.FormDataContentType()
}

// --- parsers ---

// ParseChapterTestMeta scrapes the whole-paper submit context from the paper HTML.
func ParseChapterTestMeta(html string) (ChapterTestMeta, error) {
	html = NormalizeXxtFontHTML(html)
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return ChapterTestMeta{}, fmt.Errorf("xuexitong: chapter-test meta parse error: %w", err)
	}
	get := func(id string) string { v, _ := doc.Find("input#" + id).Attr("value"); return v }
	meta := ChapterTestMeta{
		CourseId:         get("courseId"),
		ClassId:          get("classId"),
		Knowledgeid:      get("knowledgeid"),
		Cpi:              get("cpi"),
		JobId:            get("jobid"),
		TotalQuestionNum: get("totalQuestionNum"),
		AnswerId:         get("answerId"),
		WorkAnswerId:     get("workAnswerId"),
		Api:              get("api"),
		FullScore:        get("fullScore"),
		OldSchoolId:      get("oldSchoolId"),
		OldWorkId:        get("oldWorkId"),
		WorkRelationId:   get("workRelationId"),
		EncWork:          get("enc_work"),
	}
	doc.Find(".chapter-title").EachWithBreak(func(i int, s *goquery.Selection) bool {
		if v, ok := s.Attr("workname"); ok && v != "" {
			meta.Title = v
		} else {
			meta.Title = strings.TrimSpace(s.Text())
		}
		return false
	})
	return meta, nil
}

// ParseChapterTestQuestions parses the whole-paper questions. Ported from go-core
// ParseWorkQuestionAction (div.Py-mian1 sets; quesType from .quesType [..]).
func ParseChapterTestQuestions(html string) ([]ChapterTestQuestion, error) {
	html = NormalizeXxtFontHTML(html)
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("xuexitong: chapter-test questions parse error: %w", err)
	}
	if strings.Contains(doc.Find("div.chapter-content").Text(), "已截止，不能作答") {
		return nil, errors.New("已截止，不能作答")
	}
	out := make([]ChapterTestQuestion, 0)
	doc.Find("div.Py-mian1").Each(func(i int, node *goquery.Selection) {
		qid, _ := node.Attr("data")
		inner, _ := node.Html()
		qdoc, derr := goquery.NewDocumentFromReader(strings.NewReader(inner))
		if derr != nil {
			return
		}
		quesType := extractBracket(qdoc.Find(".Py-m1-title .quesType").Text())
		if strings.Contains(quesType, "辨析题") {
			quesType = XxtQTypeJudge
		} else if strings.Contains(quesType, "投票题") {
			quesType = XxtQTypeSingle
		}
		content := cleanChapterText(qdoc.Find(".Py-m1-title .workTextWrap").Text())
		q := ChapterTestQuestion{Qid: qid, Content: content}
		switch quesType {
		case XxtQTypeSingle:
			q.Type, q.TypeCode, q.Options = XxtQTypeSingle, "0", chapterChoiceOptions(qdoc, "singleChoice")
		case XxtQTypeMulti:
			q.Type, q.TypeCode, q.Options = XxtQTypeMulti, "1", chapterChoiceOptions(qdoc, "multiChoice")
		case XxtQTypeJudge:
			q.Type, q.TypeCode, q.Options = XxtQTypeJudge, "3", chapterJudgeOptions(qdoc)
		case XxtQTypeFill:
			q.Type, q.TypeCode, q.Blanks = XxtQTypeFill, "2", chapterFillBlanks(qdoc)
		case XxtQTypeShort:
			q.Type, q.TypeCode = XxtQTypeShort, "4"
		case "名词解释":
			q.Type, q.TypeCode = "名词解释", "5"
		case XxtQTypeEssay:
			q.Type, q.TypeCode = XxtQTypeEssay, "6"
		case "连线题":
			q.Type, q.TypeCode = "连线题", "11"
			q.Options, q.Selects = chapterMatching(qdoc)
		default:
			q.Type, q.TypeCode = XxtQTypeEssay, "6" // unknown → treat as essay
		}
		out = append(out, q)
	})
	return out, nil
}

func chapterChoiceOptions(qdoc *goquery.Document, className string) map[string]string {
	res := make(map[string]string)
	qdoc.Find(".answerList." + className + " li").Each(func(i int, s *goquery.Selection) {
		letter := s.Find("em.choose-opt").Text()
		if v, ok := s.Find("em.choose-opt").Attr("id-param"); ok && v != "" {
			letter = v
		}
		text := strings.TrimSpace(s.Find("cc").Contents().First().Text())
		if text == "" {
			text = strings.TrimSpace(s.Find("cc").Text())
		}
		if letter != "" {
			res[letter] = text
		}
	})
	return orderedLetters(res)
}

func chapterJudgeOptions(qdoc *goquery.Document) map[string]string {
	res := make(map[string]string)
	qdoc.Find(".answerList.panduan li").Each(func(i int, s *goquery.Selection) {
		letter := s.Find("em").Text()
		if v, _ := s.Attr("val-param"); v == "true" {
			letter = "对"
		} else if v == "false" {
			letter = "错"
		}
		text := strings.TrimSpace(s.Find("p").Contents().First().Text())
		res[letter] = text
	})
	return res
}

func chapterFillBlanks(qdoc *goquery.Document) int {
	n := 0
	qdoc.Find("ul.blankList2").Each(func(i int, s *goquery.Selection) {
		parts := strings.Split(s.Find("p").Text(), ":")
		for _, p := range parts {
			if strings.TrimSpace(p) != "" {
				n++
			}
		}
	})
	if n == 0 {
		n = 1
	}
	return n
}

func chapterMatching(qdoc *goquery.Document) (map[string]string, []string) {
	options := map[string]string{}
	var selects []string
	qdoc.Find("ul.answerList-line").Each(func(i int, s *goquery.Selection) {
		if i%2 == 0 {
			j := 0
			s.Find("li").Each(func(_ int, li *goquery.Selection) {
				j++
				options[strconv.Itoa(j)] = li.Text()
			})
		} else {
			s.Find("li").Each(func(_ int, li *goquery.Selection) {
				selects = append(selects, li.Text())
			})
		}
	})
	return options, selects
}

func orderedLetters(res map[string]string) map[string]string {
	out := make(map[string]string)
	for _, sel := range []string{"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N"} {
		if res[sel] == "" {
			break
		}
		out[sel] = res[sel]
	}
	return out
}

func extractBracket(text string) string {
	start := strings.Index(text, "[")
	end := strings.Index(text, "]")
	if start != -1 && end != -1 && end > start {
		return strings.TrimSpace(text[start+1 : end])
	}
	return ""
}

func cleanChapterText(text string) string {
	text = strings.TrimSpace(text)
	text = regexp.MustCompile(`\n+`).ReplaceAllString(text, " ")
	return regexp.MustCompile(`\s+`).ReplaceAllString(text, " ")
}
