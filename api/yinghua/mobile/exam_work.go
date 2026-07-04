package mobile

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// Standardized yinghua question types (mirrors go-core que-core/qtype strings,
// duplicated here to keep the mobile package free of que-core imports).
const (
	QTypeSingle = "单选题"
	QTypeMulti  = "多选题"
	QTypeFill   = "填空题"
	QTypeJudge  = "判断题"
	QTypeShort  = "简答题"
	QTypeOther  = "其他"
)

// WorkItem is one work (作业) listed for a node.
type WorkItem struct {
	ID        string  `json:"id"`
	Title     string  `json:"title"`
	WorkID    string  `json:"workId"`
	CourseID  string  `json:"courseId"`
	NodeID    string  `json:"nodeId"`
	Score     float64 `json:"score"`
	Allow     int     `json:"allow"`
	Frequency int     `json:"frequency"`
}

// ExamItem is one exam (考试) listed for a node.
type ExamItem struct {
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	ExamID      string  `json:"examId"`
	CourseID    string  `json:"courseId"`
	NodeID      string  `json:"nodeId"`
	Score       float64 `json:"score"`
	LimitedTime float64 `json:"limitedTime"`
}

// Topic is a single parsed exam/work question.
type Topic struct {
	AnswerID string   `json:"answerId"`
	Index    string   `json:"index"`
	Source   string   `json:"source"`
	Type     string   `json:"type"`
	Content  string   `json:"content"`
	Options  []string `json:"options"`
}

// --- HTTP endpoints (mirror go-core api/yinghua, using the mobile client) ---

// WorkDetail fetches /api/node/work.json (work list for a node).
func (c *YingHuaClient) WorkDetail(nodeID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/node/work.json?nodeId="+nodeID, map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"nodeId":   nodeID,
		"token":    c.Token,
		"terminal": "Android",
	})
	return string(b), err
}

// ExamDetail fetches /api/node/exam.json (exam list for a node).
func (c *YingHuaClient) ExamDetail(nodeID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/node/exam.json?nodeId="+nodeID, map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"nodeId":   nodeID,
		"token":    c.Token,
		"terminal": "Android",
	})
	return string(b), err
}

// StartWork opens a work for answering (GET /api/work/start.json).
func (c *YingHuaClient) StartWork(courseID, nodeID, workID string) (string, error) {
	b, err := c.get(c.PreURL + "/api/work/start.json?nodeId=" + nodeID + "&courseId=" + courseID + "&token=" + c.Token + "&workId=" + workID)
	return string(b), err
}

// GetWork fetches the work questions HTML (POST /api/work.json, empty body).
func (c *YingHuaClient) GetWork(nodeID, workID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/work.json?nodeId="+nodeID+"&workId="+workID+"&token="+c.Token, map[string]string{})
	return string(b), err
}

// StartExam opens an exam for answering (GET /api/exam/start.json).
func (c *YingHuaClient) StartExam(courseID, nodeID, examID string) (string, error) {
	b, err := c.get(c.PreURL + "/api/exam/start.json?nodeId=" + nodeID + "&courseId=" + courseID + "&token=" + c.Token + "&examId=" + examID)
	return string(b), err
}

// GetExamTopic fetches the exam questions HTML (POST /api/exam.json, JSON body).
func (c *YingHuaClient) GetExamTopic(nodeID, examID string) (string, error) {
	b, err := c.postJSON(c.PreURL+"/api/exam.json?nodeId="+nodeID+"&examId="+examID+"&token="+c.Token, "{}")
	return string(b), err
}

// SubmitWork submits one work answer (POST /api/work/submit.json).
// qType is a standardized QType* string; wireAnswers are letters (choice) or text
// (fill/short) as produced by FormatWireAnswer. finish="1" finalizes the work.
func (c *YingHuaClient) SubmitWork(workID, answerID, qType string, wireAnswers []string, finish string) (string, error) {
	fields := answerFields("workId", workID, answerID, finish, qType, wireAnswers, c.Token)
	b, err := c.postMultipartOrdered(c.PreURL+"/api/work/submit.json", fields)
	return string(b), err
}

// SubmitExam submits one exam answer (POST /api/exam/submit.json).
func (c *YingHuaClient) SubmitExam(examID, answerID, qType string, wireAnswers []string, finish string) (string, error) {
	fields := answerFields("examId", examID, answerID, finish, qType, wireAnswers, c.Token)
	b, err := c.postMultipartOrdered(c.PreURL+"/api/exam/submit.json", fields)
	return string(b), err
}

// answerFields builds the ordered multipart fields for a work/exam answer submit.
func answerFields(idKey, idVal, answerID, finish, qType string, wireAnswers []string, token string) [][2]string {
	fields := [][2]string{
		{"platform", "Android"},
		{"version", "1.4.8"},
		{idKey, idVal},
		{"terminal", "Android"},
		{"answerId", answerID},
		{"finish", finish},
		{"token", token},
	}
	switch qType {
	case QTypeSingle, QTypeJudge, QTypeShort:
		if len(wireAnswers) > 0 {
			fields = append(fields, [2]string{"answer", wireAnswers[0]})
		}
	case QTypeMulti:
		for _, v := range wireAnswers {
			fields = append(fields, [2]string{"answer[]", v})
		}
	case QTypeFill:
		for i, v := range wireAnswers {
			fields = append(fields, [2]string{"answer_" + strconv.Itoa(i+1), v})
		}
	}
	return fields
}

// --- parsers (exported for tests) ---

type listResp struct {
	Code   int    `json:"_code"`
	Status bool   `json:"status"`
	Msg    string `json:"msg"`
	Result struct {
		List []struct {
			ID          float64 `json:"id"`
			Title       string  `json:"title"`
			Score       float64 `json:"score"`
			LimitedTime float64 `json:"limitedTime"`
			CourseID    float64 `json:"courseId"`
			NodeID      float64 `json:"nodeId"`
			URL         string  `json:"url"`
			Allow       string  `json:"allow"`
			Frequency   string  `json:"frequency"`
		} `json:"list"`
	} `json:"result"`
}

// ParseWorkList parses /api/node/work.json into WorkItem list.
func ParseWorkList(raw string) ([]WorkItem, error) {
	var resp listResp
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: work list parse error: %w", err)
	}
	if !resp.Status {
		return nil, fmt.Errorf("yinghua: work list failed: %s", resp.Msg)
	}
	items := make([]WorkItem, 0, len(resp.Result.List))
	for _, o := range resp.Result.List {
		allow, _ := strconv.Atoi(o.Allow)
		freq, _ := strconv.Atoi(o.Frequency)
		items = append(items, WorkItem{
			ID:        strconv.FormatFloat(o.ID, 'f', 0, 64),
			Title:     o.Title,
			WorkID:    extractURLParam(o.URL, "workId="),
			CourseID:  strconv.FormatFloat(o.CourseID, 'f', 0, 64),
			NodeID:    strconv.FormatFloat(o.NodeID, 'f', 0, 64),
			Score:     o.Score,
			Allow:     allow,
			Frequency: freq,
		})
	}
	return items, nil
}

// ParseExamList parses /api/node/exam.json into ExamItem list.
func ParseExamList(raw string) ([]ExamItem, error) {
	var resp listResp
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: exam list parse error: %w", err)
	}
	if !resp.Status {
		return nil, fmt.Errorf("yinghua: exam list failed: %s", resp.Msg)
	}
	items := make([]ExamItem, 0, len(resp.Result.List))
	for _, o := range resp.Result.List {
		items = append(items, ExamItem{
			ID:          strconv.FormatFloat(o.ID, 'f', 0, 64),
			Title:       o.Title,
			ExamID:      extractURLParam(o.URL, "examId="),
			CourseID:    strconv.FormatFloat(o.CourseID, 'f', 0, 64),
			NodeID:      strconv.FormatFloat(o.NodeID, 'f', 0, 64),
			Score:       o.Score,
			LimitedTime: o.LimitedTime,
		})
	}
	return items, nil
}

// extractURLParam returns the value following key (e.g. "workId=") up to the next
// "&token" boundary, mirroring the go-core string splitting.
func extractURLParam(url, key string) string {
	parts := strings.SplitN(url, key, 2)
	if len(parts) < 2 {
		return ""
	}
	return strings.SplitN(parts[1], "&token", 2)[0]
}

// ParseStartResult inspects a StartWork/StartExam response. _code==9 means the
// server refused (not started / already completed / out of attempts); its msg is
// returned as the error.
func ParseStartResult(raw string) error {
	var r struct {
		Code int    `json:"_code"`
		Msg  string `json:"msg"`
	}
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		return fmt.Errorf("yinghua: start parse error: %w", err)
	}
	if r.Code == 9 {
		return fmt.Errorf("yinghua: %s", r.Msg)
	}
	return nil
}

// ParseAnswerSubmit checks a work/exam submit response; returns the server msg.
func ParseAnswerSubmit(raw string) (string, error) {
	var r struct {
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
	}
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		return "", fmt.Errorf("yinghua: answer submit parse error: %w", err)
	}
	if !r.Status {
		return r.Msg, fmt.Errorf("yinghua: answer submit failed: %s", r.Msg)
	}
	return r.Msg, nil
}

// TurnExamTopic converts the exam/work questions HTML into a Topic slice.
// Ported from go-core api/yinghua.TurnExamTopic (pure regex, no goquery).
func TurnExamTopic(examHTML string) []Topic {
	topics := make([]Topic, 0)

	topicPattern := `<li>[ \f\n\r\t\v]*<a data-id="([^\"]*)"[ \f\n\r\t\v]*href="[^\"]*"[ \f\n\r\t\v]*class="[^\"]*"[ \f\n\r\t\v]*id="[^\"]*"[ \f\n\r\t\v]*data-index="[^\"]*"[ \f\n\r\t\v]*onclick="[^\"]*">([^<]*)</a>[ \f\n\r\t\v]*</li>`
	topicRegexp := regexp.MustCompile(topicPattern)
	topicMap := make(map[string]string)
	for _, match := range topicRegexp.FindAllStringSubmatch(examHTML, -1) {
		topicMap[match[2]] = match[1] // index -> answerId
	}

	formPattern := `<form method="post" action="/api/[^/]*\/submit">([\w\W]*?)</form>`
	formRegexp := regexp.MustCompile(formPattern)
	for _, formMatch := range formRegexp.FindAllStringSubmatch(examHTML, -1) {
		topicHTML := formMatch[1]

		var num, tag, source, content string
		var selects []string

		if m := regexp.MustCompile(`<span class="num">[\D]*?([\d]+)`).FindStringSubmatch(topicHTML); len(m) > 0 {
			num = m[1]
		}
		if m := regexp.MustCompile(`<span class="tag">([\s\S]*?)</span>`).FindStringSubmatch(topicHTML); len(m) > 0 {
			tag = m[1]
		}
		if m := regexp.MustCompile(`<span[ \f\n\r\t\v]*class="txt">[^\d]*([\d]*)[^分]*分[^<]*</span>`).FindStringSubmatch(topicHTML); len(m) > 0 {
			source = m[1]
		}

		if tag == "单选" || tag == "多选" || tag == "判断" {
			if m := regexp.MustCompile(`<div[ \f\n\r\t\v]*class="content"[ \f\n\r\t\v]*style="[^\"]*">([\s\S]*?)</div>`).FindStringSubmatch(topicHTML); len(m) > 0 {
				content = m[1]
			}
			selectPattern := `<li>[^<]*<label>[^<]*<input type="([^"]*)"[^v]*value="([^"]*)"[ \f\n\r\t\v]*[checked="checked"]*[ \f\n\r\t\v]*class="[^"]*"[ \f\n\r\t\v]*name="[^"]*">[ \f\n\r\t\v]*<span class="num">([^<]*)</span>[ \f\n\r\t\v]*<span[ \f\n\r\t\v]*class="txt">([^<]*)</span>[ \f\n\r\t\v]*</label>[ \f\n\r\t\v]*</li>`
			for _, sm := range regexp.MustCompile(selectPattern).FindAllStringSubmatch(topicHTML, -1) {
				selects = append(selects, sm[4])
			}
			content = cleanContent(content)
		} else if tag == "填空" {
			if m := regexp.MustCompile(`<div[ \f\n\r\t\v]*class="content"[ \f\n\r\t\v]*style="[^\"]*">([\s\S]*?)</div>`).FindStringSubmatch(topicHTML); len(m) > 0 {
				content = m[1]
			}
			fillRegexp := regexp.MustCompile(`<input class="[^"]*" autocomplete="[^"]*" autocomplete="[^"]*" type="[^"]*" style="[^"]*" name="answer_([^"]*)" value="[^"]*"/>`)
			for _, fm := range fillRegexp.FindAllStringSubmatch(topicHTML, -1) {
				selects = append(selects, fm[1])
			}
			codeRegexp := regexp.MustCompile(`<code> class="[^"]*" autocomplete="[^"]*" autocomplete="[^"]*" type="[^"]*" style="[^"]*" name="answer_([^"]*)" value="[^"]*"[^<]*</code>`)
			for _, cm := range codeRegexp.FindAllStringSubmatch(content, -1) {
				content = strings.ReplaceAll(content, cm[0], fmt.Sprintf("（answer_%s）", cm[1]))
			}
			content = cleanContent(content)
		} else if tag == "简答" {
			if m := regexp.MustCompile(`<div[ \f\n\r\t\v]*class="content"[ \f\n\r\t\v]*style="[^\"]*">([\s\S]*?)</div>`).FindStringSubmatch(topicHTML); len(m) > 0 {
				content = m[1]
			}
		}
		for i := range selects {
			selects[i] = strings.ReplaceAll(selects[i], "&quot;", "\"")
		}
		topics = append(topics, Topic{
			AnswerID: topicMap[num],
			Index:    num,
			Source:   source,
			Type:     turnTypeStr(tag),
			Content:  content,
			Options:  selects,
		})
	}
	return topics
}

func cleanContent(content string) string {
	content = strings.ReplaceAll(content, "<p>", "")
	content = strings.ReplaceAll(content, "</p>", "\n")
	content = strings.ReplaceAll(content, "&nbsp;", "")
	return content
}

func turnTypeStr(origin string) string {
	switch origin {
	case "单选":
		return QTypeSingle
	case "多选":
		return QTypeMulti
	case "判断":
		return QTypeJudge
	case "填空":
		return QTypeFill
	case "简答":
		return QTypeShort
	}
	return QTypeOther
}

// --- answer formatting (deterministic; the AI/answering stays in the host) ---

// FormatWireAnswer converts host-supplied answer texts into the wire format the
// yinghua server expects. For choice questions (single/multi/judge) it maps each
// answer text to the closest option's LETTER (A/B/C…) via string similarity,
// mirroring go-core answerTurnResult. For fill/short it passes the text through.
// Empty results fall back to "A" for choice questions (fill/short stay empty).
func FormatWireAnswer(qType string, options, hostAnswers []string) []string {
	out := make([]string, 0)
	switch qType {
	case QTypeSingle, QTypeJudge, QTypeMulti:
		res := make([]string, 0)
		for _, item := range hostAnswers {
			res = append(res, similarityArraySelectAndFilter(item, options, res))
		}
		out = res
	case QTypeFill, QTypeShort:
		out = append(out, hostAnswers...)
	}
	if len(out) == 0 {
		switch qType {
		case QTypeSingle, QTypeJudge, QTypeMulti:
			out = []string{"A"}
		}
	}
	return out
}

var optionLetters = []string{"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q"}

// similarityArraySelectAndFilter returns the letter of the option most similar to
// target, skipping letters already chosen (filter). Mirrors go-core qutils.
func similarityArraySelectAndFilter(target string, options, filter []string) string {
	best, bestIdx := 0.0, 0
	for i := range options {
		if i >= len(optionLetters) {
			break
		}
		score := similarity(options[i], target)
		if score > best && !containsStr(filter, optionLetters[i]) {
			best = score
			bestIdx = i
		}
	}
	if bestIdx >= len(optionLetters) {
		return ""
	}
	return optionLetters[bestIdx]
}

func containsStr(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

func similarity(a, b string) float64 {
	maxLen := float64(maxInt(len(a), len(b)))
	if maxLen == 0 {
		return 1.0
	}
	return 1.0 - float64(levenshtein(a, b))/maxLen
}

func levenshtein(a, b string) int {
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
			dp[i][j] = minInt(dp[i-1][j]+1, minInt(dp[i][j-1]+1, dp[i-1][j-1]+cost))
		}
	}
	return dp[m][n]
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
