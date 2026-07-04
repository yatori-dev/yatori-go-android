package mobile

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

// Localized question-type strings (mirror que-core/qtype, kept local to avoid the
// banned que-core import).
const (
	XxtQTypeSingle = "单选题"
	XxtQTypeMulti  = "多选题"
	XxtQTypeFill   = "填空题"
	XxtQTypeJudge  = "判断题"
	XxtQTypeShort  = "简答题"
	XxtQTypeEssay  = "论述题"
	XxtQTypeOther  = "其它"
)

// WorkItem is one work/assignment listed for a course.
type WorkItem struct {
	Name      string `json:"name"`
	Status    string `json:"status"`
	TaskRefId string `json:"taskRefId"`
	CourseId  string `json:"courseId"`
	UserId    string `json:"userId"`
	ClazzId   string `json:"clazzId"`
	Type      string `json:"type"`
	EncTask   string `json:"encTask"`
	MsgId     string `json:"msgId"`
}

// WorkEnterInfo carries what's scraped from the work-enter page.
type WorkEnterInfo struct {
	QuestionTotal    int    `json:"questionTotal"`
	CaptchaCaptchaId string `json:"captchaCaptchaId"`
	ExamRelationId   string `json:"examRelationId"`
	AnswerId         string `json:"answerId"`
	Cpi              string `json:"cpi"`
	WorkAnswerId     string `json:"workAnswerId"`
	Enc              string `json:"enc"`
}

// WorkSubmitEntity carries the per-question fields needed to submit one answer.
// The host round-trips it verbatim from the question pull back to the submit call.
type WorkSubmitEntity struct {
	CourseId   string `json:"courseId"`
	WorkId     string `json:"workId"` // workRelationId
	ClassId    string `json:"classId"`
	AnswerId   string `json:"answerId"` // workRelationAnswerId
	Enc        string `json:"enc"`
	Source     string `json:"source"`
	EncWork    string `json:"encWork"`
	QuestionId string `json:"questionId"`
	Index      string `json:"index"`
	TypeCode   string `json:"typeCode"`
	Score      string `json:"score"`
}

// WorkQuestion is one parsed work question for the host to answer.
type WorkQuestion struct {
	Submit   WorkSubmitEntity `json:"submit"`
	Type     string           `json:"type"`     // localized Chinese type
	TypeCode string           `json:"typeCode"` // chaoxing code 0/1/2/3/4/6
	Content  string           `json:"content"`
	Options  []string         `json:"options"`
}

// --- HTTP endpoints ---

// redirectClient follows redirects while manually carrying cookies, and records
// the final URL (needed as the slider referer).
func (c *XxtClient) redirectClient(finalURL *string) *http.Client {
	return &http.Client{
		Transport: httpTransport(c),
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			if finalURL != nil {
				*finalURL = req.URL.String()
			}
			if len(via) > 0 {
				for _, ck := range via[0].Cookies() {
					req.AddCookie(ck)
				}
			}
			return nil
		},
	}
}

func (c *XxtClient) workHTMLGet(urlStr string, finalURL *string) (string, error) {
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
	req.Header.Add("Upgrade-Insecure-Requests", "1")
	req.Header.Add("accept-language", "zh_CN")
	req.Header.Add("X-Requested-With", "com.chaoxing.mobile")
	req.Header.Add("Connection", "keep-alive")
	addCookies(req, c)
	res, err := c.redirectClient(finalURL).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if finalURL != nil && *finalURL == "" {
		*finalURL = res.Request.URL.String()
	}
	body, err := io.ReadAll(res.Body)
	return string(body), err
}

// WorkListHtmlApi fetches the work task-list HTML for a course.
func (c *XxtClient) WorkListHtmlApi(courseId, classId, cpi string) (string, error) {
	return c.workHTMLGet("https://mooc1-api.chaoxing.com/work/task-list?courseId="+courseId+"&classId="+classId+"&cpi="+cpi, nil)
}

// WorkEnterInformHtmlApi opens a work, returning the enter HTML and the final URL
// (used as the slider referer).
func (c *XxtClient) WorkEnterInformHtmlApi(taskrefId, msgId, courseId, userId, clazzId, enterType, encTask string) (string, string, error) {
	var finalURL string
	u := "https://mooc1-api.chaoxing.com/android/mtaskmsgspecial?taskrefId=" + taskrefId + "&msgId=" + msgId +
		"&courseId=" + courseId + "&userId=" + userId + "&clazzId=" + clazzId + "&type=" + enterType + "&enc_task=" + encTask
	html, err := c.workHTMLGet(u, &finalURL)
	return html, finalURL, err
}

// WorkPaperHtmlApi pulls the work paper (first question) HTML.
func (c *XxtClient) WorkPaperHtmlApi(courseId, classId, workId, source, msgId, cpi, workAnswerId, enc string) (string, error) {
	u := "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?courseId=" + courseId + "&workId=" + workId +
		"&cpi=" + cpi + "&workAnswerId=" + workAnswerId + "&classId=" + classId + "&oldWorkId&mooc=1&msgId=" + msgId +
		"&source=" + source + "&checkIntegrity=true&enc=" + enc + "&keyboardDisplayRequiresUserAction=1"
	return c.workHTMLGet(u, nil)
}

// WorkQuestionApi pulls one work question's HTML (by index).
func (c *XxtClient) WorkQuestionApi(courseId, classId, workId, source, msgId, cpi, workAnswerId, enc string, index int) (string, error) {
	u := "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?courseId=" + courseId + "&workId=" + workId +
		"&cpi=" + cpi + "&workAnswerId=" + workAnswerId + "&classId=" + classId + "&mooc=1&source=" + source +
		"&enc=" + enc + "&keyboardDisplayRequiresUserAction=1&index=" + strconv.Itoa(index)
	return c.workHTMLGet(u, nil)
}

// SubmitWorkAnswerApi submits one work question's answer. options/hostAnswers are
// the question's option texts and the host's chosen answer texts; tempSave=true
// saves without final submit.
func (c *XxtClient) SubmitWorkAnswerApi(entity *WorkSubmitEntity, options, hostAnswers []string, tempSave bool) (string, error) {
	values := WorkAnswerForm(entity, options, hostAnswers, tempSave)
	u := "https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doNormalHomeWorkSubmit?tempSave=" + fmt.Sprintf("%v", tempSave)
	req, err := http.NewRequest("POST", u, strings.NewReader(values.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("Origin", "https://mooc1-api.chaoxing.com")
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
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

// WorkAnswerForm builds the x-www-form-urlencoded body for a work submit, encoding
// the host answer texts per question type (choice→letters via similarity, fill→
// per-blank text, short/essay→first text). Ported from go-core SubmitWorkAnswerApi.
func WorkAnswerForm(e *WorkSubmitEntity, options, hostAnswers []string, tempSave bool) url.Values {
	v := url.Values{}
	v.Set("workExamUploadUrl", "")
	v.Set("workExamUploadCrcUrl", "")
	v.Set("workRelationAnswerId", e.AnswerId)
	v.Set("knowledgeid", "0")
	v.Set("enc", e.Enc)
	v.Set("source", e.Source)
	v.Set("encWork", e.EncWork)
	// these three appear twice in the original wire payload
	v.Add("courseId", e.CourseId)
	v.Add("workRelationId", e.WorkId)
	v.Add("classId", e.ClassId)
	v.Add("courseId", e.CourseId)
	v.Add("workRelationId", e.WorkId)
	v.Add("classId", e.ClassId)
	v.Set("workTimesEnc", "")
	v.Set("questionId", e.QuestionId)
	v.Set("index", e.Index)
	v.Set("tempSave", fmt.Sprintf("%v", tempSave))

	qid := e.QuestionId
	switch e.TypeCode {
	case "0": // single
		v.Set("type"+qid, e.TypeCode)
		v.Set("score"+qid, e.Score)
		ans := ""
		for _, a := range hostAnswers {
			ans += similaritySelect(a, options)
		}
		v.Set("answer"+qid, ans)
	case "1": // multiple
		v.Set("type"+qid, e.TypeCode)
		v.Set("score"+qid, e.Score)
		ans := ""
		for _, a := range hostAnswers {
			ans += similaritySelect(a, options)
		}
		v.Set("answers"+qid, ans)
	case "3": // judge
		v.Set("type"+qid, e.TypeCode)
		v.Set("score"+qid, e.Score)
		ans := ""
		for _, a := range hostAnswers {
			if similaritySelect(a, options) == "A" {
				ans = "true"
			} else {
				ans = "false"
			}
		}
		v.Set("answer"+qid, ans)
	case "2": // fill
		v.Set("type"+qid, e.TypeCode)
		v.Set("score"+qid, e.Score)
		blankNum := ""
		for i, a := range hostAnswers {
			v.Set("answer"+qid+strconv.Itoa(i+1), a)
			blankNum += strconv.Itoa(i+1) + ","
		}
		v.Set("blankNum"+qid, blankNum)
	case "4", "6": // short / essay
		v.Set("type"+qid, e.TypeCode)
		v.Set("score"+qid, e.Score)
		ans := ""
		if len(hostAnswers) > 0 {
			ans = hostAnswers[0]
		}
		v.Set("answer"+qid, ans)
	}
	return v
}

// --- parsers ---

// ParseWorkList parses the work task-list HTML into WorkItem list.
func ParseWorkList(html string) ([]WorkItem, error) {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil, fmt.Errorf("xuexitong: work list parse error: %w", err)
	}
	items := make([]WorkItem, 0)
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
		status := strings.TrimSpace(div.Find("span").Eq(0).Text())
		items = append(items, WorkItem{
			Name:      name,
			Status:    status,
			TaskRefId: params["taskrefId"],
			CourseId:  params["courseId"],
			UserId:    params["userId"],
			ClazzId:   params["clazzId"],
			Type:      params["type"],
			EncTask:   params["enc_task"],
			MsgId:     params["msgId"],
		})
	})
	return items, nil
}

// ParseWorkEnter scrapes the work-enter HTML for question count, captcha id, and
// the cpi/answerId/enc params needed to pull the paper.
func ParseWorkEnter(html string) (WorkEnterInfo, error) {
	var info WorkEnterInfo
	if m := regexp.MustCompile(`共包含\s*(\d+)\s*道题目`).FindStringSubmatch(html); len(m) > 1 {
		info.QuestionTotal, _ = strconv.Atoi(m[1])
	}
	if strings.Contains(html, "待重做") {
		if m := regexp.MustCompile(`共\s*(\d+)\s*题`).FindStringSubmatch(html); len(m) > 1 {
			info.QuestionTotal, _ = strconv.Atoi(m[1])
		}
	}
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return info, fmt.Errorf("xuexitong: work enter parse error: %w", err)
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
	cpi, workAnswerId, enc := extractWorkParams(html)
	if cpi != "" {
		info.Cpi = cpi
	}
	if workAnswerId != "" {
		info.WorkAnswerId = workAnswerId
		if info.AnswerId == "" {
			info.AnswerId = workAnswerId
		}
	}
	info.Enc = enc
	return info, nil
}

func extractWorkParams(js string) (cpi, workAnswerId, enc string) {
	if m := regexp.MustCompile(`cpi=(\d+)`).FindStringSubmatch(js); len(m) > 1 {
		cpi = m[1]
	}
	if m := regexp.MustCompile(`workAnswerId=(\d+)`).FindStringSubmatch(js); len(m) > 1 {
		workAnswerId = m[1]
	}
	if m := regexp.MustCompile(`enc=([a-fA-F0-9]+)`).FindStringSubmatch(js); len(m) > 1 {
		enc = m[1]
	}
	return
}

// ParseWorkQuestion parses one work question's HTML into a WorkQuestion (content,
// options, submit-entity). Ported from go-core HtmlWorkQuestionTurnEntity.
func ParseWorkQuestion(html string) (WorkQuestion, error) {
	html = NormalizeXxtFontHTML(html)
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return WorkQuestion{}, fmt.Errorf("xuexitong: work question parse error: %w", err)
	}
	if strings.Contains(html, "已过时效，不能操作") {
		return WorkQuestion{}, errors.New("已过时效，不能操作!")
	}
	questionId, _ := doc.Find("#questionId").Attr("value")
	typeCode, _ := doc.Find(`input[name="type` + questionId + `"]`).Attr("value")

	var q WorkQuestion
	switch typeCode {
	case "0":
		q.Type, q.Options, q.Content = XxtQTypeSingle, choiceWorkOptions(doc), choiceWorkTitle(doc)
	case "1":
		q.Type, q.Options, q.Content = XxtQTypeMulti, choiceWorkOptions(doc), choiceWorkTitle(doc)
	case "2":
		q.Type, q.Options, q.Content = XxtQTypeFill, fillWorkBlanks(doc), textWorkTitle(doc)
	case "3":
		q.Type, q.Options, q.Content = XxtQTypeJudge, judgeWorkOptions(doc), textWorkTitle(doc)
	case "4":
		q.Type, q.Content = XxtQTypeShort, textWorkTitle(doc)
	case "6":
		q.Type, q.Content = XxtQTypeEssay, textWorkTitle(doc)
	default:
		q.Type = XxtQTypeOther
		q.Content = textWorkTitle(doc)
	}
	get := func(id string) string { v, _ := doc.Find("#" + id).Attr("value"); return v }
	getName := func(name string) string { v, _ := doc.Find(`input[name="` + name + `"]`).Attr("value"); return v }
	if qid := questionIdFromDoc(doc); qid != "" {
		questionId = qid
	}
	q.TypeCode = typeCode
	q.Submit = WorkSubmitEntity{
		CourseId:   get("courseId"),
		WorkId:     get("workId"),
		ClassId:    get("classId"),
		Enc:        get("enc"),
		Source:     get("source"),
		EncWork:    get("encWork"),
		QuestionId: questionId,
		Index:      get("index"),
		TypeCode:   typeCode,
		Score:      getName("score" + questionId),
		AnswerId:   get("testUserRelationId"),
	}
	if q.Submit.Source == "" {
		q.Submit.Source = "0"
	}
	return q, nil
}

// questionIdFromDoc returns the per-question data id from the .singleQuesId node.
func questionIdFromDoc(doc *goquery.Document) string {
	id := ""
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		if v, ok := sel.Attr("data"); ok && v != "" {
			id = v
			return false
		}
		return true
	})
	return id
}

func choiceWorkTitle(doc *goquery.Document) string {
	title := ""
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		title = extractWorkTitle(sel)
		return false
	})
	return title
}

func choiceWorkOptions(doc *goquery.Document) []string {
	var opts []string
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		resOptions := make(map[string]string)
		sel.Find(`div.centerSpan`).Each(func(i int, s *goquery.Selection) {
			letter, _ := s.Attr("id")
			text := extractWorkOptionText(s)
			if letter != "" && text != "" {
				resOptions[letter] = text
			}
		})
		for _, slt := range []string{"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N"} {
			v, ok := resOptions[slt]
			if !ok || v == "" {
				break
			}
			opts = append(opts, v)
		}
		return false
	})
	return opts
}

func judgeWorkOptions(doc *goquery.Document) []string {
	var opts []string
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.Answer`).Each(func(i int, s *goquery.Selection) {
			letter := s.Find("span.check").Text()
			text := s.Find("p.fl").Text()
			opts = append(opts, letter+text)
		})
		return false
	})
	return opts
}

func fillWorkBlanks(doc *goquery.Document) []string {
	var opts []string
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.blankItemName`).Each(func(i int, s *goquery.Selection) {
			letter, _ := s.Attr(`aria-label`)
			opts = append(opts, letter)
		})
		return false
	})
	return opts
}

func textWorkTitle(doc *goquery.Document) string {
	title := ""
	doc.Find("div.singleQuesId").EachWithBreak(func(i int, sel *goquery.Selection) bool {
		sel.Find(`.workWrap`).Each(func(i int, s *goquery.Selection) {
			title = extractQuestion(s)
		})
		return false
	})
	return title
}

// extractWorkTitle pulls the stem node (avoiding option .workWrap collisions).
func extractWorkTitle(sel *goquery.Selection) string {
	titleSel := sel.Find(`div.ans-cc.timuStyle.fontLabel.workWrap`).First()
	if titleSel.Length() == 0 {
		titleSel = sel.Find(`div.ans-cc.workWrap`).First()
	}
	if titleSel.Length() == 0 {
		titleSel = sel.Find(`.workWrap`).First()
	}
	if titleSel.Length() == 0 {
		return ""
	}
	return extractQuestion(titleSel)
}

func extractWorkOptionText(sel *goquery.Selection) string {
	text := strings.TrimSpace(sel.Text())
	if text == "" {
		text = strings.TrimSpace(sel.Find("p").Text())
	}
	return strings.TrimSpace(regexp.MustCompile(`\s+`).ReplaceAllString(text, " "))
}

// extractQuestion cleans the stem HTML to plain text (skips h3/题干 span; recurses
// into p). Ported from go-core.
func extractQuestion(tit *goquery.Selection) string {
	var sb strings.Builder
	tit.Contents().Each(func(i int, s *goquery.Selection) {
		switch goquery.NodeName(s) {
		case "h3":
			return
		case "span":
			if v, ok := s.Attr("aria-label"); ok && v == "题干" {
				return
			}
		case "p":
			sb.WriteString(extractQuestion(s))
			return
		}
		if text := strings.TrimSpace(s.Text()); text != "" {
			sb.WriteString(text)
		}
	})
	return strings.TrimSpace(sb.String())
}

// --- deterministic answer→letter mapping (AI stays in host) ---

var xxtOptionLetters = []string{"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "R", "M", "N", "O", "P", "Q"}

// similaritySelect returns the letter of the option most similar to target.
// Mirrors go-core qutils.SimilarityArraySelect.
func similaritySelect(target string, options []string) string {
	if len(options) == 0 {
		return "A"
	}
	best, bestScore := 0, -1.0
	for i := range options {
		if i >= len(xxtOptionLetters) {
			break
		}
		if s := xxtSimilarity(options[i], target); s > bestScore {
			bestScore = s
			best = i
		}
	}
	if best >= len(xxtOptionLetters) {
		return "A"
	}
	return xxtOptionLetters[best]
}

func xxtSimilarity(a, b string) float64 {
	maxLen := len(a)
	if len(b) > maxLen {
		maxLen = len(b)
	}
	if maxLen == 0 {
		return 1.0
	}
	return 1.0 - float64(xxtLevenshtein(a, b))/float64(maxLen)
}

func xxtLevenshtein(a, b string) int {
	m, n := len(a), len(b)
	dp := make([][]int, m+1)
	for i := range dp {
		dp[i] = make([]int, n+1)
		dp[i][0] = i
	}
	for j := 0; j <= n; j++ {
		dp[0][j] = j
	}
	for i := 1; i <= m; i++ {
		for j := 1; j <= n; j++ {
			cost := 0
			if a[i-1] != b[j-1] {
				cost = 1
			}
			d := dp[i-1][j] + 1
			if dp[i][j-1]+1 < d {
				d = dp[i][j-1] + 1
			}
			if dp[i-1][j-1]+cost < d {
				d = dp[i-1][j-1] + cost
			}
			dp[i][j] = d
		}
	}
	return dp[m][n]
}
