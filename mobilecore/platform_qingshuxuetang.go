package mobilecore

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"

	qsxtApi "github.com/yatori-dev/yatori-go-mobile-core/api/qingshuxuetang"
)

// qsxtCaptchaProvider fetches a captcha session. Replaceable for tests.
var qsxtCaptchaProvider = func(account string) (sessionID, imageBase64 string, err error) {
	cache := &qsxtApi.QsxtUserCache{Account: account}
	raw, err := cache.QsxtPhoneValidationCodeApi(3, nil)
	if err != nil {
		return "", "", err
	}
	var resp struct {
		HR   int `json:"hr"`
		Data struct {
			SessionID string `json:"sessionId"`
			Code      string `json:"code"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return "", "", fmt.Errorf("captcha parse error: %w", err)
	}
	if resp.HR != 0 || resp.Data.SessionID == "" {
		return "", "", fmt.Errorf("captcha request failed: %s", raw)
	}
	return resp.Data.SessionID, resp.Data.Code, nil
}

// qsxtLoginProvider submits captcha answer and returns token. Replaceable for tests.
var qsxtLoginProvider = func(account, password, sessionID, verCode string) (token string, err error) {
	cache := &qsxtApi.QsxtUserCache{
		Account:        account,
		Password:       password,
		VerCodeSession: sessionID,
		VerCode:        verCode,
	}
	raw, err := cache.QsxtPhoneLoginApi(3, nil)
	if err != nil {
		return "", err
	}
	var resp struct {
		Data struct {
			Token string `json:"token"`
		} `json:"data"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return "", fmt.Errorf("login parse error: %w", err)
	}
	if resp.Data.Token == "" {
		return "", errors.New("login failed: " + raw)
	}
	return resp.Data.Token, nil
}

// startLoginQsxt begins the qingshuxuetang OCR login flow by fetching a captcha.
func startLoginQsxt(input AccountInput) (StartLoginResult, error) {
	sessionID, imgB64, err := qsxtCaptchaProvider(input.Account)
	if err != nil {
		return StartLoginResult{}, fmt.Errorf("captcha fetch failed: %w", err)
	}
	task := pendingLogins.create("qingshuxuetang", input.Account)
	pendingLogins.setExtra(task.TaskID, map[string]interface{}{
		"sessionId": sessionID,
		"password":  input.Password,
	})
	ch := &OcrChallenge{
		TaskID:      task.TaskID,
		Platform:    "qingshuxuetang",
		Type:        ChallengeTypeImageOCR,
		ImageBase64: imgB64,
		Hint:        "数学计算验证码 — 计算图中算式结果",
	}
	return StartLoginResult{Status: LoginStatusChallenge, TaskID: task.TaskID, Challenge: ch}, nil
}

// continueLoginQsxt submits the OCR-recognised captcha answer to complete login.
func continueLoginQsxt(task *PendingLoginTask, ocrText string) (ContinueLoginResult, error) {
	password, _ := task.Extra["password"].(string)
	sessionID, _ := task.Extra["sessionId"].(string)

	token, err := qsxtLoginProvider(task.Account, password, sessionID, ocrText)
	pendingLogins.delete(task.TaskID) // always clean up, Android calls StartLogin again on failure
	if err != nil {
		return ContinueLoginResult{}, err
	}
	sess := SessionData{
		Platform: "qingshuxuetang",
		Account:  task.Account,
		Token:    token,
		Extra:    map[string]interface{}{},
	}
	return ContinueLoginResult{Status: LoginStatusDone, Session: &sess}, nil
}

// qsxtCoursesProvider fetches the raw course list. Replaceable for tests.
var qsxtCoursesProvider = func(token string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.QsxtPullCourseApi(3, nil)
}

func getCoursesQsxt(sess SessionData) (CourseListResult, error) {
	raw, err := qsxtCoursesProvider(sess.Token)
	if err != nil {
		return CourseListResult{}, err
	}
	var resp struct {
		HR   int `json:"hr"`
		Data []struct {
			ClassID    string  `json:"classId"`
			CourseName string  `json:"courseName"`
			Progress   float64 `json:"progress"`
			SchoolName string  `json:"schoolName"`
			SchoolID   string  `json:"schoolId"`
			SemesterID string  `json:"semesterId"` // = periodId for detail API
			CourseID   string  `json:"courseId"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseListResult{}, fmt.Errorf("courses parse error: %w", err)
	}
	if resp.HR != 0 {
		return CourseListResult{}, fmt.Errorf("courses request failed: %s", raw)
	}
	items := make([]CourseItem, 0, len(resp.Data))
	for _, c := range resp.Data {
		items = append(items, CourseItem{
			ID:       c.ClassID,
			Name:     c.CourseName,
			Progress: c.Progress,
			Raw: map[string]interface{}{
				"schoolName": c.SchoolName,
				"schoolId":   c.SchoolID,
				"periodId":   c.SemesterID,
				"courseId":   c.CourseID,
			},
		})
	}
	return CourseListResult{Platform: "qingshuxuetang", Courses: items}, nil
}

// qsxtCourseDetailProvider fetches course detail. Replaceable for tests.
var qsxtCourseDetailProvider = func(token, periodId, classId, schoolId, courseId string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.QsxtPullCourseDetailApi(periodId, classId, schoolId, courseId, 3, nil)
}

// getCourseDetailQsxt returns course detail metadata.
// Requires Raw fields from GetCourses: schoolId, periodId (semesterId), courseId.
func getCourseDetailQsxt(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	schoolId := strOf(input.Raw["schoolId"])
	periodId := strOf(input.Raw["periodId"])
	courseId := strOf(input.Raw["courseId"])
	if schoolId == "" || periodId == "" || courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("qingshuxuetang: courseJSON.raw must include schoolId, periodId, courseId (from GetCourses response)")
	}
	raw, err := qsxtCourseDetailProvider(sess.Token, periodId, input.ID, schoolId, courseId)
	if err != nil {
		return CourseDetailResult{}, err
	}
	// Parse generic detail — structure may vary; expose what we can
	var resp struct {
		HR   int                    `json:"hr"`
		Data map[string]interface{} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseDetailResult{}, fmt.Errorf("course detail parse error: %w", err)
	}
	if resp.HR != 0 {
		return CourseDetailResult{}, fmt.Errorf("course detail request failed: %s", raw)
	}
	item := CourseItem{ID: input.ID, Name: strOf(resp.Data["courseName"]), Raw: resp.Data}
	return CourseDetailResult{Platform: "qingshuxuetang", ParentID: input.ID, Items: []CourseItem{item}}, nil
}

// qsxtNodeProvider fetches the courseware node list. Replaceable for tests.
// nodeUrl is the pre-constructed coursewareTree URL (extract from GetCourseDetail raw response).
var qsxtNodeProvider = func(token, nodeUrl string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.QsxtPullNodeApi(nodeUrl, 3, nil)
}

// qsxtStartStudyProvider calls StartStudyApi; replaceable for tests.
var qsxtStartStudyProvider = func(token, classId, contentId, contentType, courseId, periodId, schoolId string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.StartStudyApi(classId, contentId, contentType, courseId, periodId, schoolId, 3, nil)
}

// qsxtSubmitStudyProvider calls SubmitStudyTimeApi; replaceable for tests.
var qsxtSubmitStudyProvider = func(token, schoolId, serverRecordId string, position int, isEnd bool) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.SubmitStudyTimeApi(schoolId, serverRecordId, position, isEnd, 3, nil)
}

// --- work/exam providers (platform `solution`, no AI) ---

var qsxtPullWorkListProvider = func(token, periodId, classId, schoolId, courseId string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.PullWorkListApi(periodId, classId, schoolId, courseId, 3, nil)
}

var qsxtPullWorkQuestionsProvider = func(token, classId, quizId, schoolId, courseId string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.PullWorkQuestionListApi(classId, quizId, schoolId, courseId, 3, nil)
}

var qsxtSubmitAnswerProvider = func(token, answer, questionId, quizId, schoolId string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.SubmitAnswerApi(answer, questionId, quizId, schoolId, 5, nil)
}

var qsxtSaveAnswerProvider = func(token, answersJson string) (string, error) {
	cache := &qsxtApi.QsxtUserCache{Token: token}
	return cache.SaveAnswerApi(answersJson, 3, nil)
}

// runTaskQsxt drives a qingshuxuetang study step.
//
// The Android host owns the loop/mode; mobile-core exposes single-step primitives
// selected by options.action:
//   - "start": only StartStudyApi; returns raw.serverRecordId for the host to reuse.
//   - "continue": one SubmitStudyTime(isEnd=false) against a host-supplied serverRecordId.
//   - "end": one SubmitStudyTime(isEnd=true) to finalize.
//   - "submit": one SubmitStudyTime honouring options.isEnd (default false).
//   - "" (legacy): combined StartStudy+SubmitStudyTime one-shot (default isEnd=true).
//
// The host loop is: start -> for{ Sleep(60s); continue; position+=60 } -> end,
// reusing the serverRecordId returned by "start".
func runTaskQsxt(sess SessionData, input TaskInput) (RunTaskResult, error) {
	switch strOf(input.Options["action"]) {
	case "pullWork":
		return qsxtPullWorkList(sess, input)
	case "work", "exam":
		return qsxtRunWork(sess, input)
	}

	contentId := input.ID
	if contentId == "" {
		contentId = strOf(input.Raw["nodeId"])
	}
	if contentId == "" {
		contentId = strOf(input.Raw["contentId"])
	}
	if contentId == "" {
		contentId = strOf(input.Raw["id"])
	}
	if contentId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.id (or raw.nodeId/contentId) is required")
	}

	action := strOf(input.Options["action"])
	schoolId := strOf(input.Raw["schoolId"])

	position := optInt(input.Options["position"], 0)
	// isEnd default: legacy one-shot ends immediately; continue=false; end=true.
	isEnd := optBool(input.Options["isEnd"], action == "")
	if action == "continue" {
		isEnd = false
	} else if action == "end" {
		isEnd = true
	}

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "qingshuxuetang", TaskID: contentId, Status: "dry_run"}, nil
	}

	// submit-only paths: the host already holds a serverRecordId from a prior "start".
	if action == "submit" || action == "continue" || action == "end" {
		if schoolId == "" {
			return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.raw.schoolId is required")
		}
		serverRecordId := strOf(input.Raw["serverRecordId"])
		if serverRecordId == "" {
			serverRecordId = strOf(input.Options["serverRecordId"])
		}
		if serverRecordId == "" {
			return RunTaskResult{}, fmt.Errorf("qingshuxuetang: action=%s requires raw.serverRecordId from a prior action=start", action)
		}
		if err := qsxtSubmitParsed(sess.Token, schoolId, serverRecordId, position, isEnd); err != nil {
			return RunTaskResult{}, err
		}
		return RunTaskResult{
			Platform: "qingshuxuetang",
			TaskID:   contentId,
			Status:   "submitted",
			Message:  fmt.Sprintf("position=%d isEnd=%v", position, isEnd),
			Raw:      map[string]interface{}{"serverRecordId": serverRecordId, "position": position, "isEnd": isEnd},
		}, nil
	}

	// start / legacy paths need the full StartStudy field set.
	classId := strOf(input.Raw["classId"])
	if classId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.raw.classId is required")
	}
	courseId := strOf(input.Raw["courseId"])
	if courseId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.raw.courseId is required")
	}
	periodId := strOf(input.Raw["periodId"])
	if periodId == "" {
		periodId = strOf(input.Raw["semesterId"])
	}
	if periodId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.raw.periodId is required")
	}
	if schoolId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: taskJSON.raw.schoolId is required")
	}
	contentType := strOf(input.Raw["contentType"])
	if contentType == "" {
		contentType = "11"
	}

	serverRecordId, err := qsxtStartParsed(sess.Token, classId, contentId, contentType, courseId, periodId, schoolId)
	if err != nil {
		return RunTaskResult{}, err
	}

	if action == "start" {
		return RunTaskResult{
			Platform: "qingshuxuetang",
			TaskID:   contentId,
			Status:   "started",
			Message:  fmt.Sprintf("contentType=%s", contentType),
			Raw:      map[string]interface{}{"serverRecordId": serverRecordId, "contentType": contentType},
		}, nil
	}

	// legacy combined one-shot
	if err := qsxtSubmitParsed(sess.Token, schoolId, serverRecordId, position, isEnd); err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "qingshuxuetang",
		TaskID:   contentId,
		Status:   "submitted",
		Message:  fmt.Sprintf("contentType=%s position=%d isEnd=%v", contentType, position, isEnd),
		Raw:      map[string]interface{}{"serverRecordId": serverRecordId, "position": position, "isEnd": isEnd},
	}, nil
}

// qsxtStartParsed calls StartStudyApi and returns the serverRecordId.
func qsxtStartParsed(token, classId, contentId, contentType, courseId, periodId, schoolId string) (string, error) {
	startRaw, err := qsxtStartStudyProvider(token, classId, contentId, contentType, courseId, periodId, schoolId)
	if err != nil {
		return "", fmt.Errorf("start study failed: %w", err)
	}
	var startResp struct {
		HR   int    `json:"hr"`
		Data string `json:"data"`
	}
	if err := json.Unmarshal([]byte(startRaw), &startResp); err != nil {
		return "", fmt.Errorf("qingshuxuetang: start study failed")
	}
	if startResp.HR != 0 {
		return "", fmt.Errorf("qingshuxuetang: start study failed")
	}
	if startResp.Data == "" {
		return "", fmt.Errorf("qingshuxuetang: start study failed: missing serverRecordId")
	}
	return startResp.Data, nil
}

// qsxtSubmitParsed calls SubmitStudyTimeApi and validates the response.
func qsxtSubmitParsed(token, schoolId, serverRecordId string, position int, isEnd bool) error {
	submitRaw, err := qsxtSubmitStudyProvider(token, schoolId, serverRecordId, position, isEnd)
	if err != nil {
		return fmt.Errorf("submit study time failed: %w", err)
	}
	var submitResp struct {
		HR int `json:"hr"`
	}
	if err := json.Unmarshal([]byte(submitRaw), &submitResp); err == nil && submitResp.HR != 0 {
		return fmt.Errorf("qingshuxuetang: submit study time failed")
	}
	return nil
}

// qsxtPullWorkList (options.action="pullWork") lists a course's works/exams so the
// host can decide which to answer. Requires raw.periodId(semesterId)/classId/schoolId/courseId
// (from GetCourses). Returns the list under raw.works.
func qsxtPullWorkList(sess SessionData, input TaskInput) (RunTaskResult, error) {
	periodId := strOf(input.Raw["periodId"])
	if periodId == "" {
		periodId = strOf(input.Raw["semesterId"])
	}
	classId := strOf(input.Raw["classId"])
	schoolId := strOf(input.Raw["schoolId"])
	courseId := strOf(input.Raw["courseId"])
	if periodId == "" || classId == "" || schoolId == "" || courseId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: action=pullWork requires raw.periodId, classId, schoolId, courseId")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "qingshuxuetang", TaskID: courseId, Status: "dry_run", Message: "action=pullWork"}, nil
	}
	raw, err := qsxtPullWorkListProvider(sess.Token, periodId, classId, schoolId, courseId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: pull work list failed: %w", err)
	}
	var resp struct {
		HR   int `json:"hr"`
		Data struct {
			Rows []map[string]interface{} `json:"rows"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: pull work list parse error: %w", err)
	}
	if resp.HR != 0 {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: pull work list failed: %s", raw)
	}
	works := make([]map[string]interface{}, 0, len(resp.Data.Rows))
	for _, w := range resp.Data.Rows {
		cid := strOf(w["courseId"])
		if cid == "" {
			cid = courseId
		}
		works = append(works, map[string]interface{}{
			"quizId":       strOf(w["id"]),
			"title":        strOf(w["title"]),
			"answerStatus": w["answerStatus"],
			"type":         w["type"],
			"totalTime":    w["totalTime"],
			"passScore":    w["passScore"],
			"finalExam":    w["finalExam"],
			"courseId":     cid,
			"classId":      classId,
			"schoolId":     schoolId,
		})
	}
	return RunTaskResult{
		Platform: "qingshuxuetang",
		TaskID:   courseId,
		Status:   "done",
		Message:  fmt.Sprintf("works=%d", len(works)),
		Raw:      map[string]interface{}{"works": works},
	}, nil
}

// qsxtRunWork (options.action="work"/"exam") answers one work/exam using the
// platform-provided `solution` for each question (zero AI). It pulls the questions,
// submits each solution, then saves with action 0 (save) or 1 (submit, when
// options.submit=true). Requires raw.quizId(id)/classId/schoolId/courseId; uses
// raw.totalTime for the reported timeSpend (totalTime/5, mirroring the console).
func qsxtRunWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	quizId := strOf(input.Raw["quizId"])
	if quizId == "" {
		quizId = strOf(input.Raw["id"])
	}
	if quizId == "" {
		quizId = input.ID
	}
	classId := strOf(input.Raw["classId"])
	schoolId := strOf(input.Raw["schoolId"])
	courseId := strOf(input.Raw["courseId"])
	if quizId == "" || classId == "" || schoolId == "" || courseId == "" {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: action=work requires raw.quizId(id), classId, schoolId, courseId")
	}
	totalTime := optInt(input.Raw["totalTime"], 0)
	submit := optBool(input.Options["submit"], false)

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "qingshuxuetang", TaskID: quizId, Status: "dry_run", Message: "action=work"}, nil
	}

	questionsRaw, err := qsxtPullWorkQuestionsProvider(sess.Token, classId, quizId, schoolId, courseId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: pull work questions failed: %w", err)
	}
	var qresp struct {
		HR   int `json:"hr"`
		Data struct {
			StudentQuestions []map[string]interface{} `json:"studentQuestions"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(questionsRaw), &qresp); err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: work questions parse error: %w", err)
	}
	if qresp.HR != 0 {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: pull work questions failed: %s", questionsRaw)
	}

	type qsxtAnswer struct {
		Answer     string  `json:"answer"`
		QuestionId string  `json:"questionId"`
		Score      float64 `json:"score"`
		TimeSpend  int     `json:"timeSpend"`
	}
	type submitAnswerPayload struct {
		Action         int          `json:"action"`
		ClassId        int          `json:"classId"`
		CourseId       string       `json:"courseId"`
		IssForceSubmit bool         `json:"issForceSubmit"`
		QuizId         string       `json:"quizId"`
		SchoolId       string       `json:"schoolId"`
		TimeSpend      int          `json:"timeSpend"`
		StudentAnswers []qsxtAnswer `json:"studentAnswers"`
	}

	numClassId, _ := strconv.Atoi(classId)
	payload := submitAnswerPayload{
		ClassId:        numClassId,
		CourseId:       courseId,
		IssForceSubmit: false,
		QuizId:         quizId,
		SchoolId:       schoolId,
		TimeSpend:      totalTime / 5,
		Action:         0,
	}
	if submit {
		payload.Action = 1
	}

	answers := make([]qsxtAnswer, 0, len(qresp.Data.StudentQuestions))
	missing := 0
	for _, q := range qresp.Data.StudentQuestions {
		questionId := strOf(q["questionId"])
		solution, _ := q["solution"].(string)
		if solution == "" {
			missing++ // no platform answer — would need AI (out of P0 scope)
		}
		score := 0.0
		if s, ok := q["score"].(float64); ok {
			score = s
		}
		subRaw, err := qsxtSubmitAnswerProvider(sess.Token, solution, questionId, quizId, schoolId)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("qingshuxuetang: submit answer failed: %w", err)
		}
		var subResp struct {
			HR int `json:"hr"`
		}
		if err := json.Unmarshal([]byte(subRaw), &subResp); err == nil && subResp.HR != 0 {
			return RunTaskResult{}, fmt.Errorf("qingshuxuetang: submit answer failed: %s", subRaw)
		}
		answers = append(answers, qsxtAnswer{Answer: solution, QuestionId: questionId, Score: score, TimeSpend: 0})
	}

	payload.StudentAnswers = answers
	marshal, err := json.Marshal(payload)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: marshal answers failed: %w", err)
	}
	saveRaw, err := qsxtSaveAnswerProvider(sess.Token, string(marshal))
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: save answer failed: %w", err)
	}
	var saveResp struct {
		HR int `json:"hr"`
	}
	if err := json.Unmarshal([]byte(saveRaw), &saveResp); err == nil && saveResp.HR != 0 {
		return RunTaskResult{}, fmt.Errorf("qingshuxuetang: save answer failed: %s", saveRaw)
	}

	status := "saved"
	if submit {
		status = "submitted"
	}
	return RunTaskResult{
		Platform: "qingshuxuetang",
		TaskID:   quizId,
		Status:   status,
		Message:  fmt.Sprintf("action=work %s questions=%d missingSolution=%d", status, len(answers), missing),
		Raw:      map[string]interface{}{"quizId": quizId, "questions": len(answers), "missingSolution": missing, "submit": submit},
	}, nil
}

// getTasksQsxt returns the node list for a qingshuxuetang course.
// Priority: raw.coursewareUrl (from GetCourseDetail) > raw.nodeUrl (legacy compat).
func getTasksQsxt(sess SessionData, input CourseInput) (TaskListResult, error) {
	nodeUrl := strOf(input.Raw["coursewareUrl"])
	if nodeUrl == "" {
		nodeUrl = strOf(input.Raw["nodeUrl"]) // legacy fallback
	}
	if nodeUrl == "" {
		return TaskListResult{}, fmt.Errorf("qingshuxuetang: courseJSON.raw.coursewareUrl is required (returned by GetCourseDetail)")
	}
	raw, err := qsxtNodeProvider(sess.Token, nodeUrl)
	if err != nil {
		return TaskListResult{}, err
	}
	var resp struct {
		HR   int                      `json:"hr"`
		Data []map[string]interface{} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return TaskListResult{}, fmt.Errorf("node list parse error: %w", err)
	}
	if resp.HR != 0 {
		return TaskListResult{}, fmt.Errorf("node list request failed: %s", raw)
	}
	tasks := make([]TaskItem, 0, len(resp.Data))
	for _, n := range resp.Data {
		id, _ := n["id"].(string)
		name, _ := n["name"].(string)
		status := "not_started"
		if isComplete, _ := n["iscomplete"].(string); isComplete == "已完成" {
			status = "done"
		}
		tasks = append(tasks, TaskItem{ID: id, Name: name, Status: status, Raw: n})
	}
	return TaskListResult{Platform: "qingshuxuetang", ParentID: input.ID, Tasks: tasks}, nil
}
