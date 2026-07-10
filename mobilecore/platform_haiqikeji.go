package mobilecore

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/thedevsaddam/gojsonq"
	haiqikejiAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/haiqikeji"
	haiqikejiApi "github.com/yatori-dev/yatori-go-mobile-core/api/haiqikeji"
)

func loginHaiqikeji(input AccountInput) (SessionData, error) {
	if input.URL == "" {
		return SessionData{}, fmt.Errorf("haiqikeji: url is required (school platform URL)")
	}
	cache := &haiqikejiApi.HqkjUserCache{
		PreUrl:   input.URL,
		Account:  input.Account,
		Password: input.Password,
	}
	if err := haiqikejiAgg.HqkjLoginAction(cache); err != nil {
		return SessionData{}, err
	}
	return SessionData{
		Platform: "haiqikeji",
		Account:  input.Account,
		Token:    cache.Token,
		Extra: map[string]interface{}{
			"userId":   cache.UserId,
			"schoolId": cache.SchoolId,
			"preUrl":   input.URL,
		},
	}, nil
}

func getCoursesHaiqikeji(sess SessionData) (CourseListResult, error) {
	cache := &haiqikejiApi.HqkjUserCache{
		PreUrl:   strOf(sess.Extra["preUrl"]),
		Token:    sess.Token,
		Account:  sess.Account,
		UserId:   strOf(sess.Extra["userId"]),
		SchoolId: strOf(sess.Extra["schoolId"]),
	}
	raw, err := cache.PullCourseListApi(3, nil)
	if err != nil {
		return CourseListResult{}, err
	}
	if containsAuthErr(raw) {
		return CourseListResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	items := make([]CourseItem, 0)
	if list, ok := gojsonq.New().JSONString(raw).Find("data").([]interface{}); ok {
		for _, item := range list {
			m, ok := item.(map[string]interface{})
			if !ok {
				continue
			}
			id := ""
			if v, ok := jFloat(m["id"]); ok {
				id = strconv.Itoa(int(v))
			}
			name, _ := jStr(m["name"])
			if name == "" {
				name, _ = jStr(m["courseName"])
			}
			rawCourse := map[string]interface{}{}
			if startDate, ok := jStr(m["startDate"]); ok {
				rawCourse["startDate"] = startDate
			}
			if endDate, ok := jStr(m["endDate"]); ok {
				rawCourse["endDate"] = endDate
			}
			items = append(items, CourseItem{ID: id, Name: name, Raw: rawCourse})
		}
	}
	return CourseListResult{Platform: "haiqikeji", Courses: items}, nil
}

// hqkjNodeProvider fetches chapter-nodes for a course. Replaceable for tests.
var hqkjNodeProvider = func(cache *haiqikejiApi.HqkjUserCache, courseId string) (string, error) {
	return cache.PullChapterNodeListApi(courseId, 3, nil)
}

// getTasksHaiqikeji returns nodes for a course, calling api layer directly.
// aggregation/haiqikeji is NOT used here — it contains infinite re-login loops.
func getTasksHaiqikeji(sess SessionData, input CourseInput) (TaskListResult, error) {
	cache := &haiqikejiApi.HqkjUserCache{
		PreUrl:   strOf(sess.Extra["preUrl"]),
		Token:    sess.Token,
		Account:  sess.Account,
		UserId:   strOf(sess.Extra["userId"]),
		SchoolId: strOf(sess.Extra["schoolId"]),
	}
	raw, err := hqkjNodeProvider(cache, input.ID)
	if err != nil {
		return TaskListResult{}, err
	}
	if containsAuthErr(raw) {
		return TaskListResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	tasks := make([]TaskItem, 0)
	if chapters, ok := gojsonq.New().JSONString(raw).Find("data").([]interface{}); ok {
		for _, ch := range chapters {
			chMap, ok := ch.(map[string]interface{})
			if !ok {
				continue
			}
			nodes, _ := chMap["children"].([]interface{})
			for _, n := range nodes {
				nd, ok := n.(map[string]interface{})
				if !ok {
					continue
				}
				id := ""
				if v, ok := jFloat(nd["id"]); ok {
					id = strconv.Itoa(int(v))
				}
				name, _ := jStr(nd["name"])
				videoDuration := 0
				if v, ok := jFloat(nd["videoDuration"]); ok {
					videoDuration = int(v)
				}
				nodeLock := 0
				if v, ok := jFloat(nd["nodeLock"]); ok {
					nodeLock = int(v)
				}
				tabVideo := nodeCapability(nd, "tabVideo")
				tabFile := nodeCapability(nd, "tabFile")
				tabExam := nodeCapability(nd, "tabExam")
				tabWork := nodeCapability(nd, "tabWork")
				tasks = append(tasks, TaskItem{
					ID:   id,
					Name: name,
					Type: nodeType(nd),
					Raw: map[string]interface{}{
						"courseId":      input.ID,
						"videoDuration": videoDuration,
						"nodeLock":      nodeLock,
						"tabVideo":      tabVideo,
						"tabFile":       tabFile,
						"tabExam":       tabExam,
						"tabWork":       tabWork,
					},
				})
			}
		}
	}
	return TaskListResult{Platform: "haiqikeji", ParentID: input.ID, Tasks: tasks}, nil
}

func nodeCapability(nd map[string]interface{}, key string) int {
	if value, ok := jFloat(nd[key]); ok && value > 0 {
		return int(value)
	}
	return 0
}

func nodeType(nd map[string]interface{}) string {
	if nodeCapability(nd, "tabVideo") > 0 {
		return "video"
	}
	if nodeCapability(nd, "tabFile") > 0 {
		return "document"
	}
	if nodeCapability(nd, "tabExam") > 0 {
		return "exam"
	}
	if nodeCapability(nd, "tabWork") > 0 {
		return "work"
	}
	return ""
}

// getCourseDetailHaiqikeji returns a normalized detail item carrying
// the session fields needed for GetTasks (courseId, schoolId, userId, preUrl).
// haiqikeji has no separate course-detail API; this is a field-forwarding wrapper.
func getCourseDetailHaiqikeji(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("haiqikeji: courseJSON.id (or raw.courseId) is required")
	}
	raw := map[string]interface{}{
		"kind":     "course",
		"courseId": courseId,
		"schoolId": strOf(sess.Extra["schoolId"]),
		"userId":   strOf(sess.Extra["userId"]),
		"preUrl":   strOf(sess.Extra["preUrl"]),
	}
	// preserve any extra fields from input.Raw
	for k, v := range input.Raw {
		if _, exists := raw[k]; !exists {
			raw[k] = v
		}
	}
	item := CourseItem{ID: courseId, Name: strOf(input.Raw["name"]), Raw: raw}
	return CourseDetailResult{Platform: "haiqikeji", ParentID: courseId, Items: []CourseItem{item}}, nil
}

func containsAuthErr(s string) bool {
	return strings.Contains(s, "令牌不匹配") || strings.Contains(s, "认证失败")
}

// hqkjGetProgressProvider calls PullLastProgressApi; replaceable for tests.
var hqkjGetProgressProvider = func(cache *haiqikejiApi.HqkjUserCache, nodeId string) (string, error) {
	return cache.PullLastProgressApi(nodeId, 3, nil)
}

// hqkjStartStudyProvider calls StartStudyApi; replaceable for tests.
var hqkjStartStudyProvider = func(cache *haiqikejiApi.HqkjUserCache, nodeId, courseId string) (string, error) {
	return cache.StartStudyApi(nodeId, courseId, 3, nil)
}

// hqkjSubmitStudyProvider calls SubmitStudyTimeApi; replaceable for tests.
var hqkjSubmitStudyProvider = func(cache *haiqikejiApi.HqkjUserCache, sessionId string, progress int) (string, error) {
	return cache.SubmitStudyTimeApi(sessionId, progress, 3, nil)
}

// hqkjEndStudyProvider calls EndStudyApi; replaceable for tests.
var hqkjEndStudyProvider = func(cache *haiqikejiApi.HqkjUserCache, sessionId string) (string, error) {
	return cache.EndStudyApi(sessionId, 3, nil)
}

// runTaskHaiqikeji drives a haiqikeji study step.
//
// The Android host owns the loop/mode; mobile-core exposes single-step primitives
// selected by options.action:
//   - "start": StartStudyApi only; returns raw.sessionId for the host to reuse.
//   - "submit"/"continue": one SubmitStudyTime(progress) against a host-supplied sessionId.
//   - "end": EndStudyApi against the host-supplied sessionId.
//   - "" (legacy): combined StartStudy+SubmitStudyTime one-shot (progress default 100),
//     now also surfacing raw.sessionId.
//
// The host normal-mode loop is: start -> for{ Sleep(30s); submit(nowTime/dur*100) }
// -> end, reusing the sessionId from "start" instead of restarting it each tick.
func runTaskHaiqikeji(sess SessionData, input TaskInput) (RunTaskResult, error) {
	switch strOf(input.Options["action"]) {
	case "pullWork":
		return hqkjPullWork(sess, input)
	case "pullExam":
		return hqkjPullExam(sess, input)
	case "workQuestions":
		return hqkjPullQuestions(sess, input, false)
	case "examQuestions":
		return hqkjPullQuestions(sess, input, true)
	case "work":
		return hqkjRunWork(sess, input)
	case "exam":
		return hqkjRunExam(sess, input)
	case "getProgress":
		return hqkjGetProgress(sess, input)
	}
	nodeId := input.ID
	if nodeId == "" {
		nodeId = strOf(input.Raw["nodeId"])
	}
	if nodeId == "" {
		nodeId = strOf(input.Raw["id"])
	}
	if nodeId == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: taskJSON.id (or raw.nodeId) is required")
	}
	action := strOf(input.Options["action"])
	progress := optInt(input.Options["progress"], 100)

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: nodeId, Status: "dry_run"}, nil
	}

	cache := &haiqikejiApi.HqkjUserCache{
		PreUrl:   strOf(sess.Extra["preUrl"]),
		Token:    sess.Token,
		Account:  sess.Account,
		UserId:   strOf(sess.Extra["userId"]),
		SchoolId: strOf(sess.Extra["schoolId"]),
	}

	// Session-only paths: the host already holds a sessionId from a prior "start".
	if action == "submit" || action == "continue" || action == "end" {
		sessionId := strOf(input.Raw["sessionId"])
		if sessionId == "" {
			sessionId = strOf(input.Options["sessionId"])
		}
		if sessionId == "" {
			return RunTaskResult{}, fmt.Errorf("haiqikeji: action=%s requires raw.sessionId from a prior action=start", action)
		}
		if action == "end" {
			if err := hqkjEndParsed(cache, sessionId); err != nil {
				return RunTaskResult{}, err
			}
			return RunTaskResult{Platform: "haiqikeji", TaskID: nodeId, Status: "ended",
				Message: "study session ended",
				Raw:     map[string]interface{}{"sessionId": sessionId}}, nil
		}
		if err := hqkjSubmitParsed(cache, sessionId, progress); err != nil {
			return RunTaskResult{}, err
		}
		return RunTaskResult{Platform: "haiqikeji", TaskID: nodeId, Status: "submitted",
			Message: fmt.Sprintf("progress=%d", progress),
			Raw:     map[string]interface{}{"sessionId": sessionId, "progress": progress}}, nil
	}

	courseId := strOf(input.Raw["courseId"])
	if courseId == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: taskJSON.raw.courseId is required")
	}
	sessionId, err := hqkjStartParsed(cache, nodeId, courseId)
	if err != nil {
		return RunTaskResult{}, err
	}

	if action == "start" {
		// Echo videoDuration from task Raw so Android can compute incremental progress
		// without a separate API call in normal-mode loops.
		videoDuration := optInt(input.Raw["videoDuration"], 0)
		return RunTaskResult{Platform: "haiqikeji", TaskID: nodeId, Status: "started",
			Raw: map[string]interface{}{"sessionId": sessionId, "videoDuration": videoDuration}}, nil
	}

	// legacy combined one-shot
	if err := hqkjSubmitParsed(cache, sessionId, progress); err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{Platform: "haiqikeji", TaskID: nodeId, Status: "submitted",
		Message: fmt.Sprintf("progress=%d", progress),
		Raw:     map[string]interface{}{"sessionId": sessionId, "progress": progress}}, nil
}

// hqkjStartParsed calls StartStudyApi and returns the sessionId.
func hqkjStartParsed(cache *haiqikejiApi.HqkjUserCache, nodeId, courseId string) (string, error) {
	startRaw, err := hqkjStartStudyProvider(cache, nodeId, courseId)
	if err != nil {
		return "", err
	}
	if containsAuthErr(startRaw) {
		return "", fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	if c, _ := jFloat(gojsonq.New().JSONString(startRaw).Find("code")); int(c) != 200 {
		return "", fmt.Errorf("haiqikeji: start study failed (code=%d)", int(c))
	}
	sessionId, ok := jStr(gojsonq.New().JSONString(startRaw).Find("data"))
	if !ok || sessionId == "" {
		return "", fmt.Errorf("haiqikeji: start study failed: missing data/sessionId")
	}
	return sessionId, nil
}

// hqkjSubmitParsed calls SubmitStudyTimeApi and validates the response.
func hqkjSubmitParsed(cache *haiqikejiApi.HqkjUserCache, sessionId string, progress int) error {
	submitRaw, err := hqkjSubmitStudyProvider(cache, sessionId, progress)
	if err != nil {
		return err
	}
	if containsAuthErr(submitRaw) {
		return fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	if c, _ := jFloat(gojsonq.New().JSONString(submitRaw).Find("code")); int(c) != 200 {
		return fmt.Errorf("haiqikeji: submit study time failed (code=%d)", int(c))
	}
	return nil
}

// hqkjEndParsed calls EndStudyApi and validates the response.
func hqkjEndParsed(cache *haiqikejiApi.HqkjUserCache, sessionId string) error {
	endRaw, err := hqkjEndStudyProvider(cache, sessionId)
	if err != nil {
		return err
	}
	if containsAuthErr(endRaw) {
		return fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	if c, _ := jFloat(gojsonq.New().JSONString(endRaw).Find("code")); int(c) != 200 {
		return fmt.Errorf("haiqikeji: end study failed (code=%d)", int(c))
	}
	return nil
}

// hqkjGetProgress (action="getProgress") fetches the current watch progress for a node.
// Returns raw.progress as an integer percentage (0–100). Used by the Android normal-mode
// loop to resume from the correct time position (like the console's HqkjGetNodeProgressAction).
func hqkjGetProgress(sess SessionData, input TaskInput) (RunTaskResult, error) {
	nodeId := input.ID
	if nodeId == "" {
		nodeId = strOf(input.Raw["nodeId"])
	}
	if nodeId == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: action=getProgress requires taskJSON.id (or raw.nodeId)")
	}
	cache := &haiqikejiApi.HqkjUserCache{
		PreUrl:   strOf(sess.Extra["preUrl"]),
		Token:    sess.Token,
		Account:  sess.Account,
		UserId:   strOf(sess.Extra["userId"]),
		SchoolId: strOf(sess.Extra["schoolId"]),
	}
	raw, err := hqkjGetProgressProvider(cache, nodeId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: getProgress failed: %w", err)
	}
	if containsAuthErr(raw) {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	if c, _ := jFloat(gojsonq.New().JSONString(raw).Find("code")); int(c) != 200 {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: getProgress failed (code=%d)", int(c))
	}
	// data is a string like "0.75" representing fraction completed (0.0–1.0).
	progressPct := 0
	if dataStr, ok := jStr(gojsonq.New().JSONString(raw).Find("data")); ok && dataStr != "" {
		if f, err := strconv.ParseFloat(dataStr, 64); err == nil {
			progressPct = int(f * 100)
			if progressPct > 100 {
				progressPct = 100
			}
		}
	}
	videoDuration := optInt(input.Raw["videoDuration"], 0)
	return RunTaskResult{
		Platform: "haiqikeji",
		TaskID:   nodeId,
		Status:   "done",
		Message:  fmt.Sprintf("progress=%d%%", progressPct),
		Raw: map[string]interface{}{
			"progress":      progressPct,
			"videoDuration": videoDuration,
		},
	}, nil
}

// --- exam/work primitives (host-driven AI seam) ---
//
// The Android host owns the AI answering; mobile-core only emits question text and
// receives answer text. Two-phase per work/exam, selected by options.action:
//   1. pullWork/pullExam   -> raw.works[] / raw.exams[] (pick which to answer)
//   2. workQuestions/examQuestions -> raw.questions[] ({topicId,recordId,wrId,waId,
//      type,content,options,optionIdx}); the host runs AI over stem+options.
//   3. work/exam with options.answers -> submit.
//
// SAFETY: action="work" really submits by default (console-tested path); action=
// "exam" defaults to dry-run (prepare + return, never submit) and only submits when
// options.realSubmit==true — the exam pull endpoint was rewritten from a hardcoded
// stub and is unverified against a live server.

// hqkjCachePre builds an HqkjUserCache, honouring a per-call raw.preUrl override.
func hqkjCachePre(sess SessionData, input TaskInput) *haiqikejiApi.HqkjUserCache {
	preUrl := strOf(input.Raw["preUrl"])
	if preUrl == "" {
		preUrl = strOf(sess.Extra["preUrl"])
	}
	return &haiqikejiApi.HqkjUserCache{
		PreUrl:   preUrl,
		Token:    sess.Token,
		Account:  sess.Account,
		UserId:   strOf(sess.Extra["userId"]),
		SchoolId: strOf(sess.Extra["schoolId"]),
	}
}

// --- replaceable providers (swap in tests) ---

var hqkjWorkListProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, nodeID string) (string, error) {
	return c.WorkListApi(courseID, nodeID)
}
var hqkjExamListProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, nodeID string) (string, error) {
	return c.ExamListApi(courseID, nodeID)
}
var hqkjWorkDetailProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, workID, title string) (string, error) {
	return c.WorkDetailApi(courseID, workID, title)
}
var hqkjExamDetailProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, examID, title string) (string, error) {
	return c.ExamDetailApi(courseID, examID, title)
}
var hqkjWorkStartProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, workID, classID, paperID string) (string, error) {
	return c.WorkStartApi(courseID, workID, classID, paperID)
}
var hqkjExamStartProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, examID, classID, paperID string) (string, error) {
	return c.ExamStartApi(courseID, examID, classID, paperID)
}
var hqkjWorkConsultProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, recordID string) (string, error) {
	return c.WorkConsultApi(courseID, recordID)
}
var hqkjExamConsultProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, recordID string) (string, error) {
	return c.ExamConsultApi(courseID, recordID)
}
var hqkjWorkAnswerProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, workID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
	return c.WorkAnswerAddApi(courseID, workID, topicID, recordID, wrID, waID, qType, answers)
}
var hqkjExamAnswerProvider = func(c *haiqikejiApi.HqkjUserCache, courseID, examID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
	return c.ExamAnswerAddApi(courseID, examID, topicID, recordID, wrID, waID, qType, answers)
}

// hqkjAnswerInput is one host-supplied answer, echoing the question's submit ids/
// type/options from the pull step plus the host's chosen answer texts.
type hqkjAnswerInput struct {
	TopicID   string   `json:"topicId"`
	RecordID  string   `json:"recordId"`
	WrID      string   `json:"wrId"`
	WaID      string   `json:"waId"`
	Type      int      `json:"type"`
	Options   []string `json:"options"`
	OptionIdx []string `json:"optionIdx"`
	Answers   []string `json:"answers"`
}

func parseHqkjAnswers(v interface{}) ([]hqkjAnswerInput, error) {
	if v == nil {
		return nil, fmt.Errorf("haiqikeji: options.answers is required (array of {topicId,recordId,wrId,waId,type,options,optionIdx,answers})")
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("haiqikeji: options.answers marshal error: %w", err)
	}
	var out []hqkjAnswerInput
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, fmt.Errorf("haiqikeji: options.answers parse error: %w", err)
	}
	return out, nil
}

// hqkjPullWork (action="pullWork") lists a node's works under raw.works.
func hqkjPullWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	nodeID := input.ID
	if nodeID == "" {
		nodeID = strOf(input.Raw["nodeId"])
	}
	if courseID == "" || nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: action=pullWork requires raw.courseId and taskJSON.id (or raw.nodeId)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: nodeID, Status: "dry_run", Message: "action=pullWork"}, nil
	}
	raw, err := hqkjWorkListProvider(hqkjCachePre(sess, input), courseID, nodeID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: work list failed: %w", err)
	}
	if containsAuthErr(raw) {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	quizzes, err := haiqikejiApi.ParseQuizList(raw, "work")
	if err != nil {
		return RunTaskResult{}, err
	}
	works := make([]map[string]interface{}, 0, len(quizzes))
	for _, q := range quizzes {
		works = append(works, map[string]interface{}{
			"workId": q.ID, "title": q.Title, "courseId": courseID, "nodeId": nodeID,
		})
	}
	return RunTaskResult{
		Platform: "haiqikeji", TaskID: nodeID, Status: "done",
		Message: fmt.Sprintf("works=%d", len(works)),
		Raw:     map[string]interface{}{"works": works},
	}, nil
}

// hqkjPullExam (action="pullExam") lists a node's exams under raw.exams.
func hqkjPullExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	nodeID := input.ID
	if nodeID == "" {
		nodeID = strOf(input.Raw["nodeId"])
	}
	if courseID == "" || nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: action=pullExam requires raw.courseId and taskJSON.id (or raw.nodeId)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: nodeID, Status: "dry_run", Message: "action=pullExam"}, nil
	}
	raw, err := hqkjExamListProvider(hqkjCachePre(sess, input), courseID, nodeID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: exam list failed: %w", err)
	}
	if containsAuthErr(raw) {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	quizzes, err := haiqikejiApi.ParseQuizList(raw, "exam")
	if err != nil {
		return RunTaskResult{}, err
	}
	exams := make([]map[string]interface{}, 0, len(quizzes))
	for _, q := range quizzes {
		exams = append(exams, map[string]interface{}{
			"examId": q.ID, "title": q.Title, "courseId": courseID, "nodeId": nodeID,
		})
	}
	return RunTaskResult{
		Platform: "haiqikeji", TaskID: nodeID, Status: "done",
		Message: fmt.Sprintf("exams=%d", len(exams)),
		Raw:     map[string]interface{}{"exams": exams},
	}, nil
}

// hqkjPullQuestions (action="workQuestions"/"examQuestions") pulls detail to find
// paperId/classId, starts the quiz, then (if no questions) falls back to consult.
// Emits raw.questions[] for the host to answer.
func hqkjPullQuestions(sess SessionData, input TaskInput, isExam bool) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	title := strOf(input.Raw["title"])
	quizID := input.ID
	if quizID == "" {
		if isExam {
			quizID = strOf(input.Raw["examId"])
		} else {
			quizID = strOf(input.Raw["workId"])
		}
	}
	if courseID == "" || quizID == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: question pull requires raw.courseId and taskJSON.id (or raw.workId/examId)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: quizID, Status: "dry_run", Message: "action=questions"}, nil
	}
	cache := hqkjCachePre(sess, input)

	detailFn := hqkjWorkDetailProvider
	startFn := hqkjWorkStartProvider
	consultFn := hqkjWorkConsultProvider
	if isExam {
		detailFn = hqkjExamDetailProvider
		startFn = hqkjExamStartProvider
		consultFn = hqkjExamConsultProvider
	}

	detailRaw, err := detailFn(cache, courseID, quizID, title)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: detail failed: %w", err)
	}
	if containsAuthErr(detailRaw) {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: session expired, please re-login")
	}
	records, err := haiqikejiApi.ParseDetailRecords(detailRaw)
	if err != nil {
		return RunTaskResult{}, err
	}
	var paperID, classID string
	for _, r := range records {
		if classID == "" {
			classID = r.ClassID
		}
		if paperID == "" {
			paperID = r.PaperID
		}
	}
	startPaperID := paperID
	if startPaperID == "" {
		startPaperID = "0"
	}
	startRaw, err := startFn(cache, courseID, quizID, classID, startPaperID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: start failed: %w", err)
	}
	topics, err := haiqikejiApi.ParseStartQuestions(startRaw)
	if err != nil {
		return RunTaskResult{}, err
	}
	// fallback: old-style quizzes (paperId=0) may need consult to yield questions.
	if len(topics) == 0 {
		for _, r := range records {
			consultRaw, cerr := consultFn(cache, courseID, r.ID)
			if cerr != nil {
				continue
			}
			if t2, perr := haiqikejiApi.ParseStartQuestions(consultRaw); perr == nil && len(t2) > 0 {
				topics = t2
				break
			}
		}
	}
	questions := make([]map[string]interface{}, 0, len(topics))
	for _, t := range topics {
		questions = append(questions, map[string]interface{}{
			"topicId": t.TopicID, "recordId": t.RecordID, "wrId": t.WrID, "waId": t.WaID,
			"type": t.Type, "content": t.Content, "options": t.Options, "optionIdx": t.OptionIdx,
		})
	}
	return RunTaskResult{
		Platform: "haiqikeji", TaskID: quizID, Status: "questions",
		Message: fmt.Sprintf("questions=%d", len(questions)),
		Raw:     map[string]interface{}{"questions": questions},
	}, nil
}

// hqkjRunWork (action="work") submits host-supplied answers for a work. Really
// submits by default (console-tested path). Each topic is submitted independently.
func hqkjRunWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	workID := input.ID
	if workID == "" {
		workID = strOf(input.Raw["workId"])
	}
	if courseID == "" || workID == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: action=work requires raw.courseId and taskJSON.id (or raw.workId)")
	}
	answers, err := parseHqkjAnswers(input.Options["answers"])
	if err != nil {
		return RunTaskResult{}, err
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: workID, Status: "dry_run",
			Message: fmt.Sprintf("action=work answers=%d", len(answers))}, nil
	}
	cache := hqkjCachePre(sess, input)
	ok := 0
	for _, a := range answers {
		qType := a.Type
		if qType == 0 {
			qType = haiqikejiApi.HqkjTypeSingle
		}
		wire := haiqikejiApi.FormatHqkjAnswer(qType, a.Options, a.OptionIdx, a.Answers)
		raw, err := hqkjWorkAnswerProvider(cache, courseID, workID, a.TopicID, a.RecordID, a.WrID, a.WaID, strconv.Itoa(qType), wire)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("haiqikeji: submit work answer failed: %w", err)
		}
		if _, err := haiqikejiApi.ParseAnswerResult(raw); err != nil {
			return RunTaskResult{}, err
		}
		ok++
	}
	return RunTaskResult{
		Platform: "haiqikeji", TaskID: workID, Status: "submitted",
		Message: fmt.Sprintf("submitted=%d/%d", ok, len(answers)),
		Raw:     map[string]interface{}{"submitted": ok, "total": len(answers)},
	}, nil
}

// hqkjRunExam (action="exam") saves host-supplied answers one topic at a time.
// Explicit options.dryRun remains side-effect free. options.realSubmit only preserves
// the existing caller-visible status; no final-submit endpoint is invoked here.
func hqkjRunExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	examID := input.ID
	if examID == "" {
		examID = strOf(input.Raw["examId"])
	}
	if courseID == "" || examID == "" {
		return RunTaskResult{}, fmt.Errorf("haiqikeji: action=exam requires raw.courseId and taskJSON.id (or raw.examId)")
	}
	answers, err := parseHqkjAnswers(input.Options["answers"])
	if err != nil {
		return RunTaskResult{}, err
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "haiqikeji", TaskID: examID, Status: "dry_run",
			Message: fmt.Sprintf("action=exam answers=%d", len(answers))}, nil
	}
	realSubmit := optBool(input.Options["realSubmit"], false)
	cache := hqkjCachePre(sess, input)
	ok := 0
	for _, a := range answers {
		qType := a.Type
		if qType == 0 {
			qType = haiqikejiApi.HqkjTypeSingle
		}
		wire := haiqikejiApi.FormatHqkjAnswer(qType, a.Options, a.OptionIdx, a.Answers)
		raw, err := hqkjExamAnswerProvider(cache, courseID, examID, a.TopicID, a.RecordID, a.WrID, a.WaID, strconv.Itoa(qType), wire)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("haiqikeji: submit exam answer failed: %w", err)
		}
		if _, err := haiqikejiApi.ParseAnswerResult(raw); err != nil {
			return RunTaskResult{}, err
		}
		ok++
	}
	status := "saved"
	message := fmt.Sprintf("saved=%d/%d realSubmit=false", ok, len(answers))
	rawResult := map[string]interface{}{"saved": ok, "total": len(answers), "realSubmit": false}
	if realSubmit {
		status = "submitted"
		message = fmt.Sprintf("submitted=%d/%d realSubmit=true", ok, len(answers))
		rawResult = map[string]interface{}{"submitted": ok, "total": len(answers), "realSubmit": true}
	}
	return RunTaskResult{
		Platform: "haiqikeji", TaskID: examID, Status: status,
		Message: message,
		Raw:     rawResult,
	}, nil
}
