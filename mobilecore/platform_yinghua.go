package mobilecore

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math"
	"strconv"

	yhmobile "github.com/yatori-dev/yatori-go-mobile-core/api/yinghua/mobile"
)

func yhClient(sess SessionData) *yhmobile.YingHuaClient {
	return &yhmobile.YingHuaClient{
		PreURL:  strOf(sess.Extra["preUrl"]),
		Token:   sess.Token,
		Sign:    strOf(sess.Extra["sign"]),
		Cookies: cookieStrToSlice(sess.Cookies),
	}
}

// --- replaceable providers (swap in tests) ---

var yhCaptchaProvider = func(preURL string) (imgB64 string, cookieStr string, err error) {
	b, cookies, err := yhmobile.GetCaptchaBytes(preURL)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(b), cookieSliceToStr(cookies), nil
}

var yhLoginProvider = func(preURL, account, password, code, cookieStr string) (token, sign string, err error) {
	return yhmobile.LoginWithCode(preURL, account, password, code, cookieStrToSlice(cookieStr))
}

var yhCourseListProvider = func(c *yhmobile.YingHuaClient) (string, error) {
	return c.CourseList()
}

var yhCourseDetailProvider = func(c *yhmobile.YingHuaClient, courseID string) (string, error) {
	return c.CourseDetail(courseID)
}

var yhCourseChapterProvider = func(c *yhmobile.YingHuaClient, courseID string) (string, error) {
	return c.CourseChapter(courseID)
}

var yhVideoRecordProvider = func(c *yhmobile.YingHuaClient, courseID string, page int) (string, error) {
	return c.CourseVideoRecords(courseID, page)
}

var yhPCVideoRecordProvider = func(c *yhmobile.YingHuaClient, courseID string, page int) (string, error) {
	return c.CourseVideoRecordsPC(courseID, page)
}

var yhVideoStudyStateProvider = func(c *yhmobile.YingHuaClient, nodeID string) (string, error) {
	return c.VideoStudyState(nodeID)
}

var yhSubmitStudyTimeProvider = func(c *yhmobile.YingHuaClient, nodeID, studyID string, studyTime int) (string, error) {
	return c.SubmitStudyTime(nodeID, studyID, studyTime)
}

var yhKeepAliveProvider = func(c *yhmobile.YingHuaClient) (string, error) {
	return c.KeepAlive()
}

// --- exam/work providers (swap in tests) ---

var yhWorkDetailProvider = func(c *yhmobile.YingHuaClient, nodeID string) (string, error) {
	return c.WorkDetail(nodeID)
}

var yhExamDetailProvider = func(c *yhmobile.YingHuaClient, nodeID string) (string, error) {
	return c.ExamDetail(nodeID)
}

var yhStartWorkProvider = func(c *yhmobile.YingHuaClient, courseID, nodeID, workID string) (string, error) {
	return c.StartWork(courseID, nodeID, workID)
}

var yhGetWorkProvider = func(c *yhmobile.YingHuaClient, nodeID, workID string) (string, error) {
	return c.GetWork(nodeID, workID)
}

var yhSubmitWorkProvider = func(c *yhmobile.YingHuaClient, workID, answerID, qType string, answers []string, finish string) (string, error) {
	return c.SubmitWork(workID, answerID, qType, answers, finish)
}

var yhStartExamProvider = func(c *yhmobile.YingHuaClient, courseID, nodeID, examID string) (string, error) {
	return c.StartExam(courseID, nodeID, examID)
}

var yhGetExamTopicProvider = func(c *yhmobile.YingHuaClient, nodeID, examID string) (string, error) {
	return c.GetExamTopic(nodeID, examID)
}

var yhSubmitExamProvider = func(c *yhmobile.YingHuaClient, examID, answerID, qType string, answers []string, finish string) (string, error) {
	return c.SubmitExam(examID, answerID, qType, answers, finish)
}

// --- StartLogin / ContinueLogin ---

func startLoginYinghua(input AccountInput) (StartLoginResult, error) {
	if input.URL == "" {
		return StartLoginResult{}, fmt.Errorf("yinghua: accountJSON.url (institution base URL) is required")
	}
	imgB64, cookieStr, err := yhCaptchaProvider(input.URL)
	if err != nil {
		return StartLoginResult{}, fmt.Errorf("yinghua: captcha fetch failed: %w", err)
	}
	task := pendingLogins.create("yinghua", input.Account)
	pendingLogins.setExtra(task.TaskID, map[string]interface{}{
		"preUrl":   input.URL,
		"password": input.Password,
		"cookies":  cookieStr,
	})
	ch := &OcrChallenge{
		TaskID:      task.TaskID,
		Platform:    "yinghua",
		Type:        ChallengeTypeImageOCR,
		ImageBase64: imgB64,
		Hint:        "图形验证码 — 输入图中验证码内容",
	}
	return StartLoginResult{Status: LoginStatusChallenge, TaskID: task.TaskID, Challenge: ch}, nil
}

func continueLoginYinghua(task *PendingLoginTask, ocrText string) (ContinueLoginResult, error) {
	preURL, _ := task.Extra["preUrl"].(string)
	password, _ := task.Extra["password"].(string)
	cookieStr, _ := task.Extra["cookies"].(string)

	token, sign, err := yhLoginProvider(preURL, task.Account, password, ocrText, cookieStr)
	pendingLogins.delete(task.TaskID)
	if err != nil {
		return ContinueLoginResult{}, err
	}
	sess := SessionData{
		Platform: "yinghua",
		Account:  task.Account,
		Token:    token,
		Extra: map[string]interface{}{
			"sign":   sign,
			"preUrl": preURL,
		},
	}
	return ContinueLoginResult{Status: LoginStatusDone, Session: &sess}, nil
}

// --- GetCourses ---

func getCoursesYinghua(sess SessionData) (CourseListResult, error) {
	c := yhClient(sess)
	raw, err := yhCourseListProvider(c)
	if err != nil {
		return CourseListResult{}, fmt.Errorf("yinghua: course list request failed: %w", err)
	}
	resp, err := yhmobile.ParseCourseListResp(raw)
	if err != nil {
		return CourseListResult{}, err
	}
	preURL := strOf(sess.Extra["preUrl"])
	items := make([]CourseItem, 0, len(resp.Result.List))
	for _, co := range resp.Result.List {
		id := strconv.FormatFloat(co.ID, 'f', 0, 64)
		items = append(items, CourseItem{
			ID:       id,
			Name:     co.Name,
			Progress: yhPercent(co.Progress),
			Raw: map[string]interface{}{
				"courseId":     id,
				"preUrl":       preURL,
				"mode":         co.Mode,
				"progress":     co.Progress,
				"progressPct":  yhPercent(co.Progress),
				"startDate":    co.StartDate,
				"endDate":      co.EndDate,
				"videoCount":   co.VideoCount,
				"videoLearned": co.VideoLearned,
			},
		})
	}
	return CourseListResult{Platform: "yinghua", Courses: items}, nil
}

// --- GetCourseDetail ---

func getCourseDetailYinghua(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseID := input.ID
	if courseID == "" {
		courseID = strOf(input.Raw["courseId"])
	}
	if courseID == "" {
		return CourseDetailResult{}, fmt.Errorf("yinghua: courseJSON.id (or raw.courseId) is required")
	}
	preURL := strOf(input.Raw["preUrl"])
	if preURL == "" {
		preURL = strOf(sess.Extra["preUrl"])
	}
	sessCopy := sess
	if sessCopy.Extra == nil {
		sessCopy.Extra = map[string]interface{}{}
	}
	if preURL != "" {
		sessCopy.Extra["preUrl"] = preURL
	}
	c := yhClient(sessCopy)
	raw, err := yhCourseDetailProvider(c, courseID)
	if err != nil {
		return CourseDetailResult{}, fmt.Errorf("yinghua: course detail request failed: %w", err)
	}
	resp, err := yhmobile.ParseCourseDetailResp(raw)
	if err != nil {
		return CourseDetailResult{}, err
	}
	co := resp.Result.Data
	name := co.Name
	if name == "" {
		name = strOf(input.Raw["name"])
	}
	item := CourseItem{
		ID:       courseID,
		Name:     name,
		Progress: yhPercent(co.Progress),
		Raw: map[string]interface{}{
			"courseId":     courseID,
			"preUrl":       preURL,
			"mode":         co.Mode,
			"progress":     co.Progress,
			"progressPct":  yhPercent(co.Progress),
			"startDate":    co.StartDate,
			"endDate":      co.EndDate,
			"videoCount":   co.VideoCount,
			"videoLearned": co.VideoLearned,
		},
	}
	return CourseDetailResult{Platform: "yinghua", ParentID: courseID, Items: []CourseItem{item}}, nil
}

// --- GetTasks ---

func getTasksYinghua(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseID := input.ID
	if courseID == "" {
		courseID = strOf(input.Raw["courseId"])
	}
	if courseID == "" {
		return TaskListResult{}, fmt.Errorf("yinghua: courseJSON.id (or raw.courseId) is required")
	}
	preURL := strOf(input.Raw["preUrl"])
	if preURL == "" {
		preURL = strOf(sess.Extra["preUrl"])
	}
	sessCopy := sess
	if sessCopy.Extra == nil {
		sessCopy.Extra = map[string]interface{}{}
	}
	if preURL != "" {
		sessCopy.Extra["preUrl"] = preURL
	}
	c := yhClient(sessCopy)
	raw, err := yhCourseChapterProvider(c, courseID)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("yinghua: chapter request failed: %w", err)
	}
	chapters, err := yhmobile.ParseCourseChapter(raw)
	if err != nil {
		return TaskListResult{}, err
	}
	records := yhFetchVideoRecords(c, courseID)
	pcRecords := yhFetchPCVideoRecords(c, courseID)
	tasks := make([]TaskItem, 0)
	for _, ch := range chapters {
		chaptID := strconv.FormatFloat(ch.ID, 'f', 0, 64)
		for _, node := range ch.NodeList {
			nodeID := strconv.FormatFloat(node.ID, 'f', 0, 64)
			record, hasRecord := records[nodeID]
			pcRecord, hasPCRecord := pcRecords[nodeID]
			typ := "video"
			if node.TabExam {
				typ = "exam"
			} else if node.TabWork {
				typ = "work"
			} else if !node.TabVideo {
				typ = "other"
			}
			progress := 0.0
			viewedDuration := 0
			state := node.VideoState
			status := "pending"
			if node.NodeLock != 0 {
				status = "locked"
			}
			if hasRecord {
				progress = yhPercent(record.Progress)
				viewedDuration = record.ViewedDuration
				state = record.State
				if record.VideoDuration > 0 && node.VideoDuration == "" {
					node.VideoDuration = strconv.Itoa(record.VideoDuration)
				}
				switch {
				case record.Error != 0:
					status = "error"
				case progress >= 100 || record.State == 2:
					status = "completed"
					progress = 100
				case progress > 0 || viewedDuration > 0:
					status = "in_progress"
				}
			}
			errorCode := 0
			errorMessage := ""
			if hasRecord {
				errorCode = record.Error
				errorMessage = record.ErrorMessage
			}
			if hasPCRecord {
				errorCode = pcRecord.Error
				if pcRecord.ErrorMessage != "" {
					errorMessage = pcRecord.ErrorMessage
				}
				if errorCode != 0 && status != "completed" {
					status = "error"
				}
			}
			raw := map[string]interface{}{
				"nodeId":         nodeID,
				"courseId":       courseID,
				"chapterId":      chaptID,
				"videoDuration":  node.VideoDuration,
				"nodeLock":       node.NodeLock,
				"unlockTime":     node.UnlockTime,
				"videoState":     state,
				"duration":       node.Duration,
				"index":          node.Index,
				"viewedDuration": viewedDuration,
				"progress":       progress,
				"error":          errorCode,
				"errorMessage":   errorMessage,
				"tabVideo":       node.TabVideo,
				"tabFile":        node.TabFile,
				"tabVote":        node.TabVote,
				"tabWork":        node.TabWork,
				"tabExam":        node.TabExam,
				"preUrl":         preURL,
			}
			if hasRecord {
				raw["bid"] = record.BID
				raw["viewCount"] = record.ViewCount
			}
			tasks = append(tasks, TaskItem{
				ID:       nodeID,
				Name:     node.Name,
				Type:     typ,
				Status:   status,
				Progress: progress,
				Raw:      raw,
			})
		}
	}
	return TaskListResult{Platform: "yinghua", ParentID: courseID, Tasks: tasks}, nil
}

func yhFetchVideoRecords(c *yhmobile.YingHuaClient, courseID string) map[string]yhmobile.VideoRecordItem {
	out := map[string]yhmobile.VideoRecordItem{}
	seen := map[string]bool{}
	lastPage := 1
	for page := 1; page <= lastPage; page++ {
		raw, err := yhVideoRecordProvider(c, courseID, page)
		if err != nil {
			break
		}
		parsed, err := yhmobile.ParseVideoRecordPage(raw)
		if err != nil {
			break
		}
		if parsed.PageCount > 0 {
			lastPage = parsed.PageCount
		}
		if len(parsed.Items) == 0 {
			break
		}
		duplicated := false
		for _, item := range parsed.Items {
			if item.ID == "" {
				continue
			}
			if seen[item.ID] {
				duplicated = true
				break
			}
			seen[item.ID] = true
			out[item.ID] = item
		}
		if duplicated {
			break
		}
	}
	return out
}

func yhFetchPCVideoRecords(c *yhmobile.YingHuaClient, courseID string) map[string]yhmobile.PCVideoRecordItem {
	out := map[string]yhmobile.PCVideoRecordItem{}
	seen := map[string]bool{}
	lastPage := 1
	for page := 1; page <= lastPage; page++ {
		raw, err := yhPCVideoRecordProvider(c, courseID, page)
		if err != nil {
			break
		}
		parsed, err := yhmobile.ParsePCVideoRecordPage(raw)
		if err != nil {
			break
		}
		if parsed.PageCount > 0 {
			lastPage = parsed.PageCount
		}
		if len(parsed.Items) == 0 {
			break
		}
		duplicated := false
		for _, item := range parsed.Items {
			if item.ID == "" {
				continue
			}
			if seen[item.ID] {
				duplicated = true
				break
			}
			seen[item.ID] = true
			out[item.ID] = item
		}
		if duplicated {
			break
		}
	}
	return out
}

func yhPercent(v float64) float64 {
	if math.IsNaN(v) || math.IsInf(v, 0) || v <= 0 {
		return 0
	}
	if v <= 1 {
		v *= 100
	}
	if v > 100 {
		return 100
	}
	return v
}

// runTaskYinghua drives a yinghua video study step.
//
// The Android host owns the loop/mode; mobile-core exposes single-step primitives
// selected by options.action:
//   - "state"/"start": VideoStudyState only; returns raw.studyId / videoDuration
//     so the host can drive the 5s-accumulating loop without re-probing each tick.
//   - "" (submit): SubmitStudyTime with an absolute studyTime. The host supplies
//     options.studyTime (cumulative seconds) and raw.studyId; default studyTime is
//     the full videoDuration (legacy one-shot). The server message is surfaced in
//     raw.message so the host can detect the 去红 (parallel-play) warning.
func runTaskYinghua(sess SessionData, input TaskInput) (RunTaskResult, error) {
	switch strOf(input.Options["action"]) {
	case "keepAlive":
		return yhKeepAlive(sess, input)
	case "pullWork":
		return yhPullWork(sess, input)
	case "pullExam":
		return yhPullExam(sess, input)
	case "workQuestions":
		return yhPullQuestions(sess, input, false)
	case "examQuestions":
		return yhPullQuestions(sess, input, true)
	case "work":
		return yhRunWork(sess, input)
	case "exam":
		return yhRunExam(sess, input)
	}
	nodeID := input.ID
	if nodeID == "" {
		nodeID = strOf(input.Raw["nodeId"])
	}
	if nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: taskJSON.id (or raw.nodeId) is required")
	}
	courseID := strOf(input.Raw["courseId"])
	if courseID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: taskJSON.raw.courseId is required")
	}
	preURL := strOf(input.Raw["preUrl"])
	if preURL == "" {
		preURL = strOf(sess.Extra["preUrl"])
	}
	action := strOf(input.Options["action"])

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: nodeID, Status: "dry_run"}, nil
	}

	sessCopy := sess
	if sessCopy.Extra == nil {
		sessCopy.Extra = map[string]interface{}{}
	}
	if preURL != "" {
		sessCopy.Extra["preUrl"] = preURL
	}
	c := yhClient(sessCopy)

	// studyId: prefer explicit raw.studyId, else fetch from VideoStudyState.
	studyID := strOf(input.Raw["studyId"])
	videoDuration := optInt(input.Raw["videoDuration"], 0)

	if studyID == "" || action == "state" || action == "start" {
		stateRaw, err := yhVideoStudyStateProvider(c, nodeID)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: video study state failed: %w", err)
		}
		meta, err := yhmobile.ParseNodeVideoMeta(stateRaw)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: video study state parse failed: %w", err)
		}
		if studyID == "" {
			studyID = meta.StudyID
		}
		if videoDuration == 0 {
			videoDuration = meta.VideoDuration
		}
	}
	if studyID == "" {
		studyID = "0"
	}
	if videoDuration <= 0 {
		videoDuration = 1
	}

	// state/start: hand the host the loop handle without submitting.
	if action == "state" || action == "start" {
		return RunTaskResult{
			Platform: "yinghua",
			TaskID:   nodeID,
			Status:   "started",
			Message:  fmt.Sprintf("studyId=%s videoDuration=%d", studyID, videoDuration),
			Raw: map[string]interface{}{
				"studyId":       studyID,
				"videoDuration": videoDuration,
				"nodeId":        nodeID,
			},
		}, nil
	}

	// submit: absolute cumulative seconds (host-driven), default = full duration.
	studyTime := optInt(input.Options["studyTime"], videoDuration)
	submitRaw, err := yhSubmitStudyTimeProvider(c, nodeID, studyID, studyTime)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("yinghua: submit study time failed: %w", err)
	}
	meta, err := yhmobile.ParseStudySubmitResult(submitRaw)
	if err != nil {
		return RunTaskResult{}, err
	}
	if meta.StudyID != "" {
		studyID = meta.StudyID
	}
	if meta.VideoDuration > 0 {
		videoDuration = meta.VideoDuration
	}
	return RunTaskResult{
		Platform: "yinghua",
		TaskID:   nodeID,
		Status:   "submitted",
		Message:  fmt.Sprintf("studyId=%s studyTime=%d", studyID, studyTime),
		Raw: map[string]interface{}{
			"studyId":       studyID,
			"studyTime":     studyTime,
			"videoDuration": videoDuration,
			"message":       yhSubmitMsg(submitRaw),
		},
	}, nil
}

// yhSubmitMsg best-effort extracts the server "msg" from a study-submit response,
// letting the host detect the 去红 (parallel-play) warning. Empty on parse failure.
func yhSubmitMsg(raw string) string {
	var r struct {
		Msg string `json:"msg"`
	}
	_ = json.Unmarshal([]byte(raw), &r)
	return r.Msg
}

// yhKeepAlive (options.action="keepAlive") pings /api/online.json to keep the login
// session warm. The host drives this on a ~5-min ticker. Session state is reported
// in raw.alive / raw.expired; on expiry the host should re-login (needs the App's
// retained credentials). This is session-level, so no nodeId/courseId is required.
func yhKeepAlive(sess SessionData, input TaskInput) (RunTaskResult, error) {
	preURL := strOf(input.Raw["preUrl"])
	if preURL == "" {
		preURL = strOf(sess.Extra["preUrl"])
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: "keepAlive", Status: "dry_run", Message: "action=keepAlive"}, nil
	}
	sessCopy := sess
	if sessCopy.Extra == nil {
		sessCopy.Extra = map[string]interface{}{}
	}
	if preURL != "" {
		sessCopy.Extra["preUrl"] = preURL
	}
	c := yhClient(sessCopy)
	raw, err := yhKeepAliveProvider(c)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("yinghua: keepAlive failed: %w", err)
	}
	alive, expired := yhmobile.ParseKeepAliveResult(raw)
	return RunTaskResult{
		Platform: "yinghua",
		TaskID:   "keepAlive",
		Status:   "done",
		Message:  fmt.Sprintf("alive=%v expired=%v", alive, expired),
		Raw:      map[string]interface{}{"alive": alive, "expired": expired},
	}, nil
}

// --- exam/work primitives (host-driven AI seam) ---
//
// The Android host owns the AI answering; mobile-core only emits question text and
// receives answer text. The flow is two-phase per work/exam:
//   1. pullWork/pullExam  -> raw.works[] / raw.exams[] (pick which to answer)
//   2. workQuestions/examQuestions -> raw.questions[] ({answerId,index,type,content,
//      options}); the host runs AI over each question's stem+options.
//   3. work/exam with options.answers (the host's answers) -> submit.
//
// SAFETY: action="work" really submits by default; action="exam" defaults to
// dry-run (prepare + return, never submit) and only submits when
// options.realSubmit==true — exams cannot be retaken, mirroring the original
// console's examDryRun guard.

// yhAnswerInput is one host-supplied answer, echoing the question's answerId/type/
// options from the pull step plus the host's chosen answer texts.
type yhAnswerInput struct {
	AnswerID string   `json:"answerId"`
	Type     string   `json:"type"`
	Options  []string `json:"options"`
	Answers  []string `json:"answers"`
}

// yhClientPre builds a client, honouring a per-call raw.preUrl override.
func yhClientPre(sess SessionData, input TaskInput) *yhmobile.YingHuaClient {
	preURL := strOf(input.Raw["preUrl"])
	if preURL == "" {
		preURL = strOf(sess.Extra["preUrl"])
	}
	sessCopy := sess
	if sessCopy.Extra == nil {
		sessCopy.Extra = map[string]interface{}{}
	}
	if preURL != "" {
		sessCopy.Extra["preUrl"] = preURL
	}
	return yhClient(sessCopy)
}

func parseYhAnswers(v interface{}) ([]yhAnswerInput, error) {
	if v == nil {
		return nil, fmt.Errorf("yinghua: options.answers is required (array of {answerId,type,options,answers})")
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("yinghua: options.answers marshal error: %w", err)
	}
	var out []yhAnswerInput
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, fmt.Errorf("yinghua: options.answers parse error: %w", err)
	}
	return out, nil
}

// yhPullWork (action="pullWork") lists a node's works under raw.works.
func yhPullWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	nodeID := input.ID
	if nodeID == "" {
		nodeID = strOf(input.Raw["nodeId"])
	}
	if nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: action=pullWork requires taskJSON.id (or raw.nodeId)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: nodeID, Status: "dry_run", Message: "action=pullWork"}, nil
	}
	raw, err := yhWorkDetailProvider(yhClientPre(sess, input), nodeID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("yinghua: work detail failed: %w", err)
	}
	items, err := yhmobile.ParseWorkList(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	works := make([]map[string]interface{}, 0, len(items))
	for _, w := range items {
		works = append(works, map[string]interface{}{
			"workId":    w.WorkID,
			"title":     w.Title,
			"score":     w.Score,
			"courseId":  w.CourseID,
			"nodeId":    w.NodeID,
			"allow":     w.Allow,
			"frequency": w.Frequency,
		})
	}
	return RunTaskResult{
		Platform: "yinghua", TaskID: nodeID, Status: "done",
		Message: fmt.Sprintf("works=%d", len(works)),
		Raw:     map[string]interface{}{"works": works},
	}, nil
}

// yhPullExam (action="pullExam") lists a node's exams under raw.exams.
func yhPullExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	nodeID := input.ID
	if nodeID == "" {
		nodeID = strOf(input.Raw["nodeId"])
	}
	if nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: action=pullExam requires taskJSON.id (or raw.nodeId)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: nodeID, Status: "dry_run", Message: "action=pullExam"}, nil
	}
	raw, err := yhExamDetailProvider(yhClientPre(sess, input), nodeID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("yinghua: exam detail failed: %w", err)
	}
	items, err := yhmobile.ParseExamList(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	exams := make([]map[string]interface{}, 0, len(items))
	for _, e := range items {
		exams = append(exams, map[string]interface{}{
			"examId":      e.ExamID,
			"title":       e.Title,
			"score":       e.Score,
			"limitedTime": e.LimitedTime,
			"courseId":    e.CourseID,
			"nodeId":      e.NodeID,
		})
	}
	return RunTaskResult{
		Platform: "yinghua", TaskID: nodeID, Status: "done",
		Message: fmt.Sprintf("exams=%d", len(exams)),
		Raw:     map[string]interface{}{"exams": exams},
	}, nil
}

// yhPullQuestions (action="workQuestions"/"examQuestions") starts the work/exam and
// returns its parsed questions under raw.questions for the host to answer.
func yhPullQuestions(sess SessionData, input TaskInput, isExam bool) (RunTaskResult, error) {
	courseID := strOf(input.Raw["courseId"])
	nodeID := strOf(input.Raw["nodeId"])
	if courseID == "" || nodeID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: question pull requires raw.courseId and raw.nodeId")
	}
	c := yhClientPre(sess, input)

	var idVal, idKind string
	if isExam {
		idKind = "examId"
		idVal = input.ID
		if idVal == "" {
			idVal = strOf(input.Raw["examId"])
		}
	} else {
		idKind = "workId"
		idVal = input.ID
		if idVal == "" {
			idVal = strOf(input.Raw["workId"])
		}
	}
	if idVal == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: question pull requires taskJSON.id (or raw.%s)", idKind)
	}

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: idVal, Status: "dry_run", Message: "action=questions"}, nil
	}

	var startRaw, topicRaw string
	var err error
	if isExam {
		if startRaw, err = yhStartExamProvider(c, courseID, nodeID, idVal); err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: start exam failed: %w", err)
		}
		if err = yhmobile.ParseStartResult(startRaw); err != nil {
			return RunTaskResult{}, err
		}
		if topicRaw, err = yhGetExamTopicProvider(c, nodeID, idVal); err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: get exam topic failed: %w", err)
		}
	} else {
		if startRaw, err = yhStartWorkProvider(c, courseID, nodeID, idVal); err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: start work failed: %w", err)
		}
		if err = yhmobile.ParseStartResult(startRaw); err != nil {
			return RunTaskResult{}, err
		}
		if topicRaw, err = yhGetWorkProvider(c, nodeID, idVal); err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: get work failed: %w", err)
		}
	}

	topics := yhmobile.TurnExamTopic(topicRaw)
	questions := make([]map[string]interface{}, 0, len(topics))
	for _, t := range topics {
		questions = append(questions, map[string]interface{}{
			"answerId": t.AnswerID,
			"index":    t.Index,
			"type":     t.Type,
			"content":  t.Content,
			"options":  t.Options,
		})
	}
	return RunTaskResult{
		Platform: "yinghua", TaskID: idVal, Status: "questions",
		Message: fmt.Sprintf("questions=%d", len(questions)),
		Raw:     map[string]interface{}{"questions": questions},
	}, nil
}

// yhRunWork (action="work") submits host-supplied answers for a work. Each answer
// is saved (finish="0"); if options.finalize (default true) the work is finalized
// (finish="1") after the last answer. Really submits by default.
func yhRunWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	workID := input.ID
	if workID == "" {
		workID = strOf(input.Raw["workId"])
	}
	if workID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: action=work requires taskJSON.id (or raw.workId)")
	}
	answers, err := parseYhAnswers(input.Options["answers"])
	if err != nil {
		return RunTaskResult{}, err
	}
	finalize := optBool(input.Options["finalize"], true)

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: workID, Status: "dry_run",
			Message: fmt.Sprintf("action=work answers=%d", len(answers))}, nil
	}

	c := yhClientPre(sess, input)
	var last *yhAnswerInput
	for i := range answers {
		a := answers[i]
		wire := yhmobile.FormatWireAnswer(a.Type, a.Options, a.Answers)
		raw, err := yhSubmitWorkProvider(c, workID, a.AnswerID, a.Type, wire, "0")
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: submit work answer failed: %w", err)
		}
		if _, err := yhmobile.ParseAnswerSubmit(raw); err != nil {
			return RunTaskResult{}, err
		}
		last = &answers[i]
	}
	finalized := false
	if finalize && last != nil {
		wire := yhmobile.FormatWireAnswer(last.Type, last.Options, last.Answers)
		raw, err := yhSubmitWorkProvider(c, workID, last.AnswerID, last.Type, wire, "1")
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: finalize work failed: %w", err)
		}
		if _, err := yhmobile.ParseAnswerSubmit(raw); err != nil {
			return RunTaskResult{}, err
		}
		finalized = true
	}
	return RunTaskResult{
		Platform: "yinghua", TaskID: workID, Status: "submitted",
		Message: fmt.Sprintf("answers=%d finalized=%v", len(answers), finalized),
		Raw:     map[string]interface{}{"answers": len(answers), "finalized": finalized},
	}, nil
}

// yhRunExam (action="exam") prepares/submits host-supplied answers for an exam.
// DEFAULT dry-run: answers are formatted but NOT submitted unless
// options.realSubmit==true. With realSubmit, each answer is saved (finish="0") and
// the exam is finalized (finish="1") when options.finalize (default true).
func yhRunExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	examID := input.ID
	if examID == "" {
		examID = strOf(input.Raw["examId"])
	}
	if examID == "" {
		return RunTaskResult{}, fmt.Errorf("yinghua: action=exam requires taskJSON.id (or raw.examId)")
	}
	answers, err := parseYhAnswers(input.Options["answers"])
	if err != nil {
		return RunTaskResult{}, err
	}
	realSubmit := optBool(input.Options["realSubmit"], false)
	finalize := optBool(input.Options["finalize"], true)

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "yinghua", TaskID: examID, Status: "dry_run",
			Message: fmt.Sprintf("action=exam answers=%d", len(answers))}, nil
	}

	// Default safety guard: never submit an exam unless explicitly asked.
	if !realSubmit {
		return RunTaskResult{
			Platform: "yinghua", TaskID: examID, Status: "dry_run",
			Message: fmt.Sprintf("exam dry-run (set options.realSubmit=true to submit); prepared=%d", len(answers)),
			Raw:     map[string]interface{}{"prepared": len(answers), "realSubmit": false},
		}, nil
	}

	c := yhClientPre(sess, input)
	var last *yhAnswerInput
	for i := range answers {
		a := answers[i]
		wire := yhmobile.FormatWireAnswer(a.Type, a.Options, a.Answers)
		raw, err := yhSubmitExamProvider(c, examID, a.AnswerID, a.Type, wire, "0")
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: submit exam answer failed: %w", err)
		}
		if _, err := yhmobile.ParseAnswerSubmit(raw); err != nil {
			return RunTaskResult{}, err
		}
		last = &answers[i]
	}
	finalized := false
	if finalize && last != nil {
		wire := yhmobile.FormatWireAnswer(last.Type, last.Options, last.Answers)
		raw, err := yhSubmitExamProvider(c, examID, last.AnswerID, last.Type, wire, "1")
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("yinghua: finalize exam failed: %w", err)
		}
		if _, err := yhmobile.ParseAnswerSubmit(raw); err != nil {
			return RunTaskResult{}, err
		}
		finalized = true
	}
	return RunTaskResult{
		Platform: "yinghua", TaskID: examID, Status: "submitted",
		Message: fmt.Sprintf("answers=%d finalized=%v realSubmit=true", len(answers), finalized),
		Raw:     map[string]interface{}{"answers": len(answers), "finalized": finalized, "realSubmit": true},
	}, nil
}
