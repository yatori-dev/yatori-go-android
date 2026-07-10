package mobilecore

// 学习通exam答题

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

// --- providers (swap in tests) ---

var xxtExamListProvider = func(c *xxtmobile.XxtClient, courseId, classId, cpi string) (string, error) {
	return c.ExamListHtmlApi(courseId, classId, cpi)
}
var xxtExamEnterProvider = func(c *xxtmobile.XxtClient, taskRefId, msgId, courseId, userId, clazzId, typ, encTask string) (string, string, error) {
	return c.ExamEnterInformHtmlApi(taskRefId, msgId, courseId, userId, clazzId, typ, encTask)
}
var xxtExamPaperProvider = func(c *xxtmobile.XxtClient, courseId, classId, examId, source, examAnswerId, cpi, captchavalidate, jt string) (string, error) {
	return c.ExamPaperHtmlApi(courseId, classId, examId, source, examAnswerId, cpi, captchavalidate, jt)
}
var xxtExamQuestionProvider = func(c *xxtmobile.XxtClient, courseId, classId, tId, id, cpi, remainTimeParam, enc, relUpd string, index int) (string, error) {
	return c.ExamQuestionApi(courseId, classId, tId, id, cpi, remainTimeParam, enc, relUpd, index)
}
var xxtExamSubmitProvider = func(c *xxtmobile.XxtClient, e *xxtmobile.ExamSubmitEntity, options, answers []string, tempSave bool) (string, error) {
	return c.SubmitExamAnswerApi(e, options, answers, tempSave)
}

// xxtPullExamList (action="pullExamList") lists a course's exams under raw.exams.
func xxtPullExamList(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	if courseId == "" || classId == "" || cpi == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=pullExamList requires raw.courseId, classId, cpi")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: courseId, Status: "dry_run", Message: "action=pullExamList"}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtExamListProvider(c, courseId, classId, cpi)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam list failed: %w", err)
	}
	items, err := xxtmobile.ParseExamList(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	exams := make([]map[string]interface{}, 0, len(items))
	for _, ex := range items {
		exams = append(exams, map[string]interface{}{
			"name": ex.Name, "status": ex.Status, "remainTime": ex.RemainTime, "taskRefId": ex.TaskRefId,
			"courseId": ex.CourseId, "userId": ex.UserId, "clazzId": ex.ClazzId,
			"type": ex.Type, "encTask": ex.EncTask, "msgId": ex.MsgId, "classId": classId, "cpi": cpi,
		})
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: courseId, Status: "done",
		Message: fmt.Sprintf("exams=%d", len(exams)),
		Raw:     map[string]interface{}{"exams": exams},
	}, nil
}

// xxtEnterExam (action="enterExam") opens an exam (slider if present), opens the
// paper, and returns the exam-session ctx for pulling/submitting questions.
func xxtEnterExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	taskRefId := strOf(input.Raw["taskRefId"])
	courseId := strOf(input.Raw["courseId"])
	clazzId := strOf(input.Raw["clazzId"])
	if clazzId == "" {
		clazzId = strOf(input.Raw["classId"])
	}
	cpi := strOf(input.Raw["cpi"])
	msgId := strOf(input.Raw["msgId"])
	typ := strOf(input.Raw["type"])
	encTask := strOf(input.Raw["encTask"])
	if taskRefId == "" || courseId == "" || clazzId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=enterExam requires raw.taskRefId, courseId, clazzId/classId")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: taskRefId, Status: "dry_run", Message: "action=enterExam"}, nil
	}
	c := xxtClient(sess)
	userId := xxtUserId(sess, input, c)

	enterHTML, refererURL, err := xxtExamEnterProvider(c, taskRefId, msgId, courseId, userId, clazzId, typ, encTask)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam enter failed: %w", err)
	}
	info, err := xxtmobile.ParseExamEnter(enterHTML)
	if err != nil {
		return RunTaskResult{}, err
	}
	if cpi == "" {
		cpi = info.Cpi
	}
	validate := ""
	if info.CaptchaCaptchaId != "" {
		validate, err = xxtSliderProvider(c, info.CaptchaCaptchaId, refererURL)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("xuexitong: slider failed: %w", err)
		}
	}
	paperHTML, err := xxtExamPaperProvider(c, courseId, clazzId, info.ExamRelationId, "0", info.AnswerId, cpi, validate, "0")
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam paper open failed: %w", err)
	}
	// first question carries enc/encRemainTime/encLastUpdateTime needed for pulls
	first, _ := xxtmobile.ParseExamQuestion(paperHTML)
	enc, encRemain, encLast := first.Submit.Enc, first.Submit.EncRemainTime, first.Submit.EncLastUpdateTime
	return RunTaskResult{
		Platform: "xuexitong", TaskID: taskRefId, Status: "entered",
		Message: fmt.Sprintf("questionTotal=%d", info.QuestionTotal),
		Raw: map[string]interface{}{
			"questionTotal":     info.QuestionTotal,
			"courseId":          courseId,
			"classId":           clazzId,
			"examRelationId":    info.ExamRelationId,
			"answerId":          info.AnswerId,
			"taskRefId":         taskRefId,
			"cpi":               cpi,
			"enc":               enc,
			"encRemainTime":     encRemain,
			"encLastUpdateTime": encLast,
		},
	}, nil
}

// xxtExamQuestion (action="examQuestion") pulls one exam question (by raw.index).
func xxtExamQuestion(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	examRelationId := strOf(input.Raw["examRelationId"])
	answerId := strOf(input.Raw["answerId"])
	cpi := strOf(input.Raw["cpi"])
	enc := strOf(input.Raw["enc"])
	encRemain := strOf(input.Raw["encRemainTime"])
	encLast := strOf(input.Raw["encLastUpdateTime"])
	taskRefId := strOf(input.Raw["taskRefId"])
	if courseId == "" || classId == "" || examRelationId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=examQuestion requires raw.courseId, classId, examRelationId (from enterExam)")
	}
	index := optInt(input.Options["index"], 0)
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: examRelationId, Status: "dry_run", Message: fmt.Sprintf("action=examQuestion index=%d", index)}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtExamQuestionProvider(c, courseId, classId, examRelationId, answerId, cpi, encRemain, enc, encLast, index)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam question fetch failed: %w", err)
	}
	q, err := xxtmobile.ParseExamQuestion(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	// backfill exam-level ids the per-question HTML may omit
	if q.Submit.TestPaperId == "" {
		q.Submit.TestPaperId = examRelationId
	}
	if q.Submit.TestUserRelationId == "" {
		q.Submit.TestUserRelationId = answerId
	}
	if q.Submit.CourseId == "" {
		q.Submit.CourseId = courseId
	}
	if q.Submit.ClassId == "" {
		q.Submit.ClassId = classId
	}
	if q.Submit.Cpi == "" {
		q.Submit.Cpi = cpi
	}
	if q.Submit.Enc == "" {
		q.Submit.Enc = enc
	}
	if q.Submit.EncRemainTime == "" {
		q.Submit.EncRemainTime = encRemain
	}
	if q.Submit.EncLastUpdateTime == "" {
		q.Submit.EncLastUpdateTime = encLast
	}
	q.Submit.Tid = taskRefId
	q.Submit.AnswerId = answerId
	if q.Submit.RemainTimeParam == "" {
		q.Submit.RemainTimeParam = encRemain
	}
	submitMap := map[string]interface{}{}
	b, _ := json.Marshal(q.Submit)
	_ = json.Unmarshal(b, &submitMap)
	return RunTaskResult{
		Platform: "xuexitong", TaskID: examRelationId, Status: "question",
		Message: fmt.Sprintf("index=%d type=%s", index, q.Type),
		Raw: map[string]interface{}{
			"index": index, "type": q.Type, "typeCode": q.TypeCode,
			"content": q.Content, "options": q.Options, "submit": submitMap,
		},
	}, nil
}

// xxtExamSubmitInput is the host-supplied payload for action="exam".
type xxtExamSubmitInput struct {
	Submit  xxtmobile.ExamSubmitEntity `json:"submit"`
	Options []string                   `json:"options"`
	Answers []string                   `json:"answers"`
}

// xxtSubmitExam (action="exam") submits one exam question's host answers.
// DEFAULT DRY-RUN unless options.realSubmit==true; isSubmit=true finalizes (tempSave=false).
func xxtSubmitExam(sess SessionData, input TaskInput) (RunTaskResult, error) {
	in, err := parseXxtExamSubmit(input.Options["question"])
	if err != nil {
		return RunTaskResult{}, err
	}
	if in.Submit.QuestionId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=exam requires options.question.submit.questionId")
	}
	isSubmit := optBool(input.Options["isSubmit"], false)
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: "dry_run",
			Message: fmt.Sprintf("action=exam isSubmit=%v answers=%d", isSubmit, len(in.Answers))}, nil
	}
	if !optBool(input.Options["realSubmit"], false) {
		return RunTaskResult{
			Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: "dry_run",
			Message: fmt.Sprintf("exam dry-run (set options.realSubmit=true to submit); answers=%d", len(in.Answers)),
			Raw:     map[string]interface{}{"realSubmit": false, "isSubmit": isSubmit},
		}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtExamSubmitProvider(c, &in.Submit, in.Options, in.Answers, !isSubmit)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam submit failed: %w", err)
	}
	resp, parseErr := parseXxtExamSubmitResponse(raw)
	if isSubmit && resp.RetryAfterMinutes > 0 {
		return RunTaskResult{
			Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: "submit_wait",
			Message: resp.Message,
			Raw: map[string]interface{}{
				"isSubmit": true, "realSubmit": true, "message": resp.Message, "raw": raw,
				"retryAfterMinutes": resp.RetryAfterMinutes,
			},
		}, nil
	}
	if parseErr != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam submit response invalid: %w; raw=%s", parseErr, raw)
	}
	if !resp.Accepted {
		return RunTaskResult{}, fmt.Errorf("xuexitong: exam submit rejected: %s", raw)
	}
	status := "saved"
	if isSubmit {
		status = "submitted"
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: status,
		Message: resp.Message,
		Raw:     map[string]interface{}{"isSubmit": isSubmit, "realSubmit": true, "message": resp.Message, "raw": raw},
	}, nil
}

var xxtExamMinimumSubmitTimeRE = regexp.MustCompile(`考试\s*(\d+)\s*分钟内不允许提交考试`)

type xxtExamSubmitResponse struct {
	Accepted          bool
	Message           string
	RetryAfterMinutes int
}

func parseXxtExamSubmitResponse(raw string) (xxtExamSubmitResponse, error) {
	var payload struct {
		Status interface{} `json:"status"`
		Msg    string      `json:"msg"`
	}
	jsonErr := json.Unmarshal([]byte(raw), &payload)
	searchText := raw
	if jsonErr == nil && payload.Msg != "" {
		searchText += "\n" + payload.Msg
	}
	if match := xxtExamMinimumSubmitTimeRE.FindStringSubmatch(searchText); len(match) == 2 {
		minutes, err := strconv.Atoi(match[1])
		if err == nil && minutes > 0 {
			return xxtExamSubmitResponse{Message: payload.Msg, RetryAfterMinutes: minutes}, nil
		}
	}
	if jsonErr != nil {
		return xxtExamSubmitResponse{}, jsonErr
	}
	accepted, ok := xxtExamStatusBool(payload.Status)
	if !ok {
		return xxtExamSubmitResponse{Message: payload.Msg}, fmt.Errorf("missing or invalid status %v", payload.Status)
	}
	return xxtExamSubmitResponse{Accepted: accepted, Message: payload.Msg}, nil
}

func xxtExamStatusBool(value interface{}) (bool, bool) {
	switch typed := value.(type) {
	case bool:
		return typed, true
	case string:
		switch strings.ToLower(strings.TrimSpace(typed)) {
		case "true", "success", "ok", "1":
			return true, true
		case "false", "fail", "failed", "error", "0":
			return false, true
		default:
			return false, false
		}
	case float64:
		if typed == 0 {
			return false, true
		}
		if typed == 1 {
			return true, true
		}
	}
	return false, false
}

func parseXxtExamSubmit(v interface{}) (xxtExamSubmitInput, error) {
	if v == nil {
		return xxtExamSubmitInput{}, fmt.Errorf("xuexitong: options.question is required ({submit,options,answers})")
	}
	b, err := json.Marshal(v)
	if err != nil {
		return xxtExamSubmitInput{}, fmt.Errorf("xuexitong: options.question marshal error: %w", err)
	}
	var out xxtExamSubmitInput
	if err := json.Unmarshal(b, &out); err != nil {
		return xxtExamSubmitInput{}, fmt.Errorf("xuexitong: options.question parse error: %w", err)
	}
	return out, nil
}
