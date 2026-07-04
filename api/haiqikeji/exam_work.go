package haiqikeji

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// 海旗原始题型编码（提交用，原样回传）。
const (
	HqkjTypeSingle = 1 // 单选
	HqkjTypeMulti  = 2 // 多选
	HqkjTypeJudge  = 3 // 判断
	HqkjTypeFill   = 4 // 填空
	HqkjTypeShort  = 5 // 简答
)

// 作业/考试条目。
type HqkjQuiz struct {
	ID    string `json:"id"`    // workId 或 examId
	Title string `json:"title"`
}

// HqkjRecord 是 work_detail/exam_detail 里的一条答题记录。
type HqkjRecord struct {
	ID      string `json:"id"`      // 记录 id（paperId=0 的老式作业用它走 consult 拉题）
	PaperID string `json:"paperId"` // 固定试卷 id（>0 时走 start 拉题）
	ClassID string `json:"classId"` // 班级 id（start 必填）
}

// HqkjTopic 是一道解析出来的题目。OptionIdx 与 Options 一一对应（A/B/C…）。
type HqkjTopic struct {
	TopicID   string   `json:"topicId"`
	RecordID  string   `json:"recordId"`
	WrID      string   `json:"wrId"`
	WaID      string   `json:"waId"`
	CateBid   string   `json:"cateBid"`
	CateMid   string   `json:"cateMid"`
	Type      int      `json:"type"`
	Content   string   `json:"content"`
	Options   []string `json:"options"`
	OptionIdx []string `json:"optionIdx"`
}

// --- HTTP 请求小工具（复用 cache 会话/代理，TLS 跳验，统一 UA/headers） ---

func (cache *HqkjUserCache) httpClient() *http.Client {
	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	return &http.Client{Timeout: 30 * time.Second, Transport: tr}
}

const hqkjUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

func (cache *HqkjUserCache) ewGet(urlStr string) (string, error) {
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("accept", "application/json, text/plain, */*")
	req.Header.Set("authorization", cache.Token)
	req.Header.Set("user-agent", hqkjUA)
	resp, err := cache.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	return string(body), err
}

func (cache *HqkjUserCache) ewPostJSON(urlStr, payload string) (string, error) {
	req, err := http.NewRequest("POST", urlStr, strings.NewReader(payload))
	if err != nil {
		return "", err
	}
	req.Header.Set("authorization", cache.Token)
	req.Header.Set("content-type", "application/json")
	req.Header.Set("accept", "application/json, text/plain, */*")
	req.Header.Set("user-agent", hqkjUA)
	resp, err := cache.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	return string(body), err
}

// --- 列表 / 详情 / 拉题 / 提交 接口（全部真实参数化） ---

// WorkListApi 拉取章节作业列表（/api/user/yee_course_student_work_list）。
// 与既有 PullWorkInfoApi 同址，这里另起一份以保持 exam_work 自洽。
func (cache *HqkjUserCache) WorkListApi(courseID, nodeID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_work_list?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&courseId=" + courseID + "&nodeId=" + nodeID)
}

// ExamListApi 拉取章节考试列表（/api/user/yee_course_student_exam_list）。
// 替代写死 whxyart 的旧 PullExamListApi。
func (cache *HqkjUserCache) ExamListApi(courseID, nodeID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_exam_list?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&courseId=" + courseID + "&nodeId=" + nodeID)
}

// WorkDetailApi 拉作业详情（取记录 id / paperId / classId）。
func (cache *HqkjUserCache) WorkDetailApi(courseID, workID, title string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_work_detail?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&workId=" + workID + "&courseId=" + courseID + "&title=" + url.QueryEscape(title))
}

// ExamDetailApi 拉考试详情（取记录 id / paperId / classId）。
func (cache *HqkjUserCache) ExamDetailApi(courseID, examID, title string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_exam_detail?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&examId=" + examID + "&courseId=" + courseID + "&title=" + url.QueryEscape(title))
}

// WorkStartApi 开始作业并拉题（/api/user/yee_course_student_work_start）。
func (cache *HqkjUserCache) WorkStartApi(courseID, workID, classID, paperID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_work_start?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&courseId=" + courseID + "&workId=" + workID +
		"&platform=pc&createUserId=" + cache.UserId + "&state=1&classId=" + classID + "&paperId=" + paperID +
		"&random=&randData=%257B%257D&randNumber=")
}

// ExamStartApi 开始考试并拉题（/api/user/yee_course_student_exam_start）。
func (cache *HqkjUserCache) ExamStartApi(courseID, examID, classID, paperID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_course_student_exam_start?schoolId=" + cache.SchoolId +
		"&studentId=" + cache.UserId + "&courseId=" + courseID + "&examId=" + examID +
		"&platform=pc&createUserId=" + cache.UserId + "&state=1&classId=" + classID + "&paperId=" + paperID +
		"&random=&randData=%257B%257D&randNumber=")
}

// WorkConsultApi paperId=0 老式作业的回退拉题（/api/user/yee_work_record_consult_list）。
// recordId 实际传 work_detail 里的记录 id。
func (cache *HqkjUserCache) WorkConsultApi(courseID, recordID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_work_record_consult_list?schoolId=" + cache.SchoolId +
		"&userId=" + cache.UserId + "&workId=" + recordID + "&courseId=" + courseID)
}

// ExamConsultApi paperId=0 老式考试的回退拉题（/api/user/yee_exam_record_consult_list）。
func (cache *HqkjUserCache) ExamConsultApi(courseID, recordID string) (string, error) {
	return cache.ewGet(cache.PreUrl + "/api/user/yee_exam_record_consult_list?schoolId=" + cache.SchoolId +
		"&userId=" + cache.UserId + "&examId=" + recordID + "&courseId=" + courseID)
}

// WorkAnswerAddApi 提交单题作业答案（/api/user/yee_work_answer_add）。
func (cache *HqkjUserCache) WorkAnswerAddApi(courseID, workID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
	payload := hqkjAnswerPayload(cache, "workId", workID, courseID, topicID, recordID, wrID, waID, qType, answers)
	return cache.ewPostJSON(cache.PreUrl+"/api/user/yee_work_answer_add", payload)
}

// ExamAnswerAddApi 提交单题考试答案（/api/user/yee_exam_answer_add）。
func (cache *HqkjUserCache) ExamAnswerAddApi(courseID, examID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
	payload := hqkjAnswerPayload(cache, "examId", examID, courseID, topicID, recordID, wrID, waID, qType, answers)
	return cache.ewPostJSON(cache.PreUrl+"/api/user/yee_exam_answer_add", payload)
}

// hqkjAnswerPayload 构造作业/考试提交 payload。recordId 必带（缺则服务端“系统异常”），
// 其值与 wrId 互补取非空；并带全字段最大化成功率（对齐 console 实测铁律）。
func hqkjAnswerPayload(cache *HqkjUserCache, idKey, idVal, courseID, topicID, recordID, wrID, waID, qType string, answers []string) string {
	if recordID == "" {
		recordID = wrID
	}
	if wrID == "" {
		wrID = recordID
	}
	if recordID == "" {
		recordID = "0"
	}
	if wrID == "" {
		wrID = "0"
	}
	if waID == "" {
		waID = "0"
	}
	answersData, _ := json.Marshal(answers)
	return `{"schoolId":` + cache.SchoolId + `,"courseId":` + courseID + `,"userId":` + cache.UserId +
		`,"` + idKey + `":` + idVal + `,"recordId":` + recordID + `,"wrId":` + wrID + `,"waId":` + waID +
		`,"topicId":` + topicID + `,"answer":` + string(answersData) + `,"type":` + qType + `}`
}

// --- 解析（导出供测试） ---

type hqkjOptionRaw struct {
	Idx    string `json:"idx"`
	Answer string `json:"answer"`
}

type hqkjQuizRaw struct {
	WorkID float64 `json:"workId"`
	ExamID float64 `json:"examId"`
	Title  string  `json:"title"`
}

type hqkjRecordRaw struct {
	ID      float64 `json:"id"`
	PaperID float64 `json:"paperId"`
	ClassID float64 `json:"classId"`
}

type hqkjTopicRaw struct {
	ID       float64         `json:"id"`
	TopicID  float64         `json:"topicId"`
	RecordID float64         `json:"recordId"`
	WrID     float64         `json:"wrId"`
	WaID     float64         `json:"waId"`
	CateBid  float64         `json:"cateBid"`
	CateMid  float64         `json:"cateMid"`
	Type     float64         `json:"type"`
	Topic    string          `json:"topic"`
	Option   []hqkjOptionRaw `json:"option"`
}

type hqkjEnvelope struct {
	Code int    `json:"code"`
	Msg  string `json:"msg"`
	Data struct {
		WorkInfo   []hqkjQuizRaw   `json:"workInfo"`
		ExamInfo   []hqkjQuizRaw   `json:"examInfo"`
		WorkDetail []hqkjRecordRaw `json:"workDetail"`
		ExamDetail []hqkjRecordRaw `json:"examDetail"`
		WorkTopics []hqkjTopicRaw  `json:"workTopics"`
		ExamTopics []hqkjTopicRaw  `json:"examTopics"`
		WorkResult []hqkjTopicRaw  `json:"workResult"`
	} `json:"data"`
}

func fmtIntF(v float64) string { return strconv.Itoa(int(v)) }

// ParseQuizList 解析作业/考试列表。kind="work" 取 workInfo/workId；kind="exam" 取 examInfo/examId。
func ParseQuizList(raw, kind string) ([]HqkjQuiz, error) {
	var env hqkjEnvelope
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		return nil, fmt.Errorf("haiqikeji: quiz list parse error: %w", err)
	}
	arr := env.Data.WorkInfo
	if kind == "exam" {
		arr = env.Data.ExamInfo
	}
	out := make([]HqkjQuiz, 0, len(arr))
	for _, it := range arr {
		id := it.WorkID
		if kind == "exam" {
			id = it.ExamID
		}
		if id == 0 {
			continue
		}
		out = append(out, HqkjQuiz{ID: fmtIntF(id), Title: it.Title})
	}
	return out, nil
}

// ParseDetailRecords 解析 work_detail/exam_detail 的记录列表（id / paperId>0 / classId>0）。
func ParseDetailRecords(raw string) ([]HqkjRecord, error) {
	var env hqkjEnvelope
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		return nil, fmt.Errorf("haiqikeji: detail parse error: %w", err)
	}
	arr := env.Data.WorkDetail
	if len(arr) == 0 {
		arr = env.Data.ExamDetail
	}
	out := make([]HqkjRecord, 0, len(arr))
	for _, r := range arr {
		var rec HqkjRecord
		if r.ID != 0 {
			rec.ID = fmtIntF(r.ID)
		}
		if r.PaperID > 0 {
			rec.PaperID = fmtIntF(r.PaperID)
		}
		if r.ClassID > 0 {
			rec.ClassID = fmtIntF(r.ClassID)
		}
		if rec.ID != "" {
			out = append(out, rec)
		}
	}
	return out, nil
}

// ParseStartQuestions 解析 work_start/exam_start/consult 返回的题目。
// 题目数组依次尝试 data.workTopics / data.examTopics / data.workResult。
func ParseStartQuestions(raw string) ([]HqkjTopic, error) {
	var env hqkjEnvelope
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		return nil, fmt.Errorf("haiqikeji: questions parse error: %w", err)
	}
	arr := env.Data.WorkTopics
	if len(arr) == 0 {
		arr = env.Data.ExamTopics
	}
	if len(arr) == 0 {
		arr = env.Data.WorkResult
	}
	out := make([]HqkjTopic, 0, len(arr))
	for _, it := range arr {
		var t HqkjTopic
		if it.ID != 0 { // work_start: 题目 id
			t.TopicID = fmtIntF(it.ID)
		}
		if it.TopicID != 0 { // consult: topicId
			t.TopicID = fmtIntF(it.TopicID)
		}
		if it.RecordID != 0 {
			t.RecordID = fmtIntF(it.RecordID)
		}
		if it.WrID != 0 {
			t.WrID = fmtIntF(it.WrID)
		}
		if it.WaID != 0 {
			t.WaID = fmtIntF(it.WaID)
		}
		if it.CateBid != 0 {
			t.CateBid = fmtIntF(it.CateBid)
		}
		if it.CateMid != 0 {
			t.CateMid = fmtIntF(it.CateMid)
		}
		t.Type = int(it.Type)
		t.Content = stripHTML(it.Topic)
		for _, o := range it.Option {
			t.Options = append(t.Options, o.Answer)
			t.OptionIdx = append(t.OptionIdx, o.Idx)
		}
		if t.TopicID != "" && len(t.Options) > 0 {
			out = append(out, t)
		}
	}
	return out, nil
}

// ParseAnswerResult 校验一次答案提交（code==200 为成功），返回服务端 msg。
func ParseAnswerResult(raw string) (string, error) {
	var r struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
	}
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		return "", fmt.Errorf("haiqikeji: answer submit parse error: %w", err)
	}
	if r.Code != 200 {
		return r.Msg, fmt.Errorf("haiqikeji: answer submit failed (code=%d): %s", r.Code, r.Msg)
	}
	return r.Msg, nil
}

var htmlTagRe = regexp.MustCompile(`<[^>]+>`)

// stripHTML 去掉题面里的 HTML 标签与零宽/实体字符，得到纯文本喂给宿主。
func stripHTML(s string) string {
	s = htmlTagRe.ReplaceAllString(s, "")
	s = strings.ReplaceAll(s, "&nbsp;", " ")
	s = strings.ReplaceAll(s, "Feff", "")
	return strings.TrimSpace(s)
}

// --- 答案格式化（确定性；AI 留在宿主） ---

// FormatHqkjAnswer 把宿主给回的「答案文本」转换为海旗服务端要的提交格式。
// 选择题(单选/多选/判断)：把每个答案文本用相似度匹配到最接近选项，取其服务端 idx；
// 填空/简答：原样透传文本。选择题匹配不到时兜底取第一个 idx。
func FormatHqkjAnswer(qType int, options, optionIdx, hostAnswers []string) []string {
	switch qType {
	case HqkjTypeSingle, HqkjTypeMulti, HqkjTypeJudge:
		out := make([]string, 0)
		used := make([]string, 0)
		for _, ans := range hostAnswers {
			idx := matchOptionIdx(ans, options, optionIdx, used)
			if idx != "" {
				out = append(out, idx)
				used = append(used, idx)
			}
		}
		if len(out) == 0 && len(optionIdx) > 0 { // 兜底：取第一个选项
			out = append(out, optionIdx[0])
		}
		return out
	default: // 填空/简答/其它：文本透传
		out := make([]string, 0, len(hostAnswers))
		out = append(out, hostAnswers...)
		return out
	}
}

// matchOptionIdx 用编辑距离相似度把答案文本匹配回选项 idx，跳过已选 idx。
func matchOptionIdx(target string, options, optionIdx, used []string) string {
	best, bestScore := -1, -1.0
	for i := range options {
		if i >= len(optionIdx) {
			break
		}
		if containsStr(used, optionIdx[i]) {
			continue
		}
		if s := similarity(options[i], target); s > bestScore {
			bestScore = s
			best = i
		}
	}
	if best < 0 {
		return ""
	}
	return optionIdx[best]
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
