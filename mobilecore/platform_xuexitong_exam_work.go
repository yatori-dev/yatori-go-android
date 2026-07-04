package mobilecore

// 学习通作业答题

import (
	"encoding/json"
	"fmt"
	"strings"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

// --- replaceable providers (swap in tests) ---

var xxtWorkListProvider = func(c *xxtmobile.XxtClient, courseId, classId, cpi string) (string, error) {
	return c.WorkListHtmlApi(courseId, classId, cpi)
}
var xxtWorkEnterProvider = func(c *xxtmobile.XxtClient, taskRefId, msgId, courseId, userId, clazzId, typ, encTask string) (string, string, error) {
	return c.WorkEnterInformHtmlApi(taskRefId, msgId, courseId, userId, clazzId, typ, encTask)
}
var xxtWorkPaperProvider = func(c *xxtmobile.XxtClient, courseId, classId, workId, source, msgId, cpi, workAnswerId, enc string) (string, error) {
	return c.WorkPaperHtmlApi(courseId, classId, workId, source, msgId, cpi, workAnswerId, enc)
}
var xxtWorkQuestionProvider = func(c *xxtmobile.XxtClient, courseId, classId, workId, source, msgId, cpi, workAnswerId, enc string, index int) (string, error) {
	return c.WorkQuestionApi(courseId, classId, workId, source, msgId, cpi, workAnswerId, enc, index)
}
var xxtWorkSubmitProvider = func(c *xxtmobile.XxtClient, entity *xxtmobile.WorkSubmitEntity, options, answers []string, tempSave bool) (string, error) {
	return c.SubmitWorkAnswerApi(entity, options, answers, tempSave)
}
var xxtSliderProvider = func(c *xxtmobile.XxtClient, captchaId, referer string) (string, error) {
	return c.SliderPass(captchaId, referer)
}

// xxtUserId resolves the userId from raw / client / session.
func xxtUserId(sess SessionData, input TaskInput, c *xxtmobile.XxtClient) string {
	if v := strOf(input.Raw["userId"]); v != "" {
		return v
	}
	if c.UserID != "" {
		return c.UserID
	}
	return strOf(sess.Extra["userId"])
}

// xxtPullWorkList (action="pullWorkList") lists a course's works under raw.works.
func xxtPullWorkList(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	if courseId == "" || classId == "" || cpi == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=pullWorkList requires raw.courseId, classId, cpi")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: courseId, Status: "dry_run", Message: "action=pullWorkList"}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtWorkListProvider(c, courseId, classId, cpi)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work list failed: %w", err)
	}
	items, err := xxtmobile.ParseWorkList(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	works := make([]map[string]interface{}, 0, len(items))
	for _, w := range items {
		works = append(works, map[string]interface{}{
			"name": w.Name, "status": w.Status, "taskRefId": w.TaskRefId,
			"courseId": w.CourseId, "userId": w.UserId, "clazzId": w.ClazzId,
			"type": w.Type, "encTask": w.EncTask, "msgId": w.MsgId,
			"classId": classId, "cpi": cpi,
		})
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: courseId, Status: "done",
		Message: fmt.Sprintf("works=%d", len(works)),
		Raw:     map[string]interface{}{"works": works},
	}, nil
}

// xxtEnterWork (action="enterWork") opens a work (solving the slider if present)
// and returns the work-session ctx the host needs to pull/submit questions.
func xxtEnterWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
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
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=enterWork requires raw.taskRefId, courseId, clazzId/classId")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: taskRefId, Status: "dry_run", Message: "action=enterWork"}, nil
	}
	c := xxtClient(sess)
	userId := xxtUserId(sess, input, c)

	enterHTML, refererURL, err := xxtWorkEnterProvider(c, taskRefId, msgId, courseId, userId, clazzId, typ, encTask)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work enter failed: %w", err)
	}
	info, err := xxtmobile.ParseWorkEnter(enterHTML)
	if err != nil {
		return RunTaskResult{}, err
	}
	if cpi == "" {
		cpi = info.Cpi
	}
	// solve slider if the server presented one
	if info.CaptchaCaptchaId != "" {
		if _, serr := xxtSliderProvider(c, info.CaptchaCaptchaId, refererURL); serr != nil {
			return RunTaskResult{}, fmt.Errorf("xuexitong: slider failed: %w", serr)
		}
	}
	// register entry (best-effort); surface an expiry error if returned
	paperHTML, err := xxtWorkPaperProvider(c, courseId, clazzId, taskRefId, "0", msgId, cpi, info.AnswerId, info.Enc)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work paper open failed: %w", err)
	}
	if strings.Contains(paperHTML, "已过时效，不能操作") {
		return RunTaskResult{}, fmt.Errorf("xuexitong: 已过时效，不能操作!")
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: taskRefId, Status: "entered",
		Message: fmt.Sprintf("questionTotal=%d", info.QuestionTotal),
		Raw: map[string]interface{}{
			"questionTotal": info.QuestionTotal,
			"courseId":      courseId,
			"classId":       clazzId,
			"workId":        taskRefId,
			"msgId":         msgId,
			"cpi":           cpi,
			"answerId":      info.AnswerId,
			"enc":           info.Enc,
			"source":        "0",
		},
	}, nil
}

// xxtWorkQuestion (action="workQuestion") pulls one work question (by raw.index)
// and emits its stem/options + the submit ctx for the host to round-trip.
func xxtWorkQuestion(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	workId := strOf(input.Raw["workId"])
	msgId := strOf(input.Raw["msgId"])
	cpi := strOf(input.Raw["cpi"])
	answerId := strOf(input.Raw["answerId"])
	enc := strOf(input.Raw["enc"])
	source := strOf(input.Raw["source"])
	if source == "" {
		source = "0"
	}
	if courseId == "" || classId == "" || workId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=workQuestion requires raw.courseId, classId, workId (from enterWork)")
	}
	index := optInt(input.Options["index"], 0)
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: workId, Status: "dry_run", Message: fmt.Sprintf("action=workQuestion index=%d", index)}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtWorkQuestionProvider(c, courseId, classId, workId, source, msgId, cpi, answerId, enc, index)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work question fetch failed: %w", err)
	}
	q, err := xxtmobile.ParseWorkQuestion(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	// backfill ids the per-question HTML may omit
	if q.Submit.AnswerId == "" {
		q.Submit.AnswerId = answerId
	}
	if q.Submit.CourseId == "" {
		q.Submit.CourseId = courseId
	}
	if q.Submit.ClassId == "" {
		q.Submit.ClassId = classId
	}
	if q.Submit.Enc == "" {
		q.Submit.Enc = enc
	}
	submitMap := map[string]interface{}{}
	b, _ := json.Marshal(q.Submit)
	_ = json.Unmarshal(b, &submitMap)
	return RunTaskResult{
		Platform: "xuexitong", TaskID: workId, Status: "question",
		Message: fmt.Sprintf("index=%d type=%s", index, q.Type),
		Raw: map[string]interface{}{
			"index":    index,
			"type":     q.Type,
			"typeCode": q.TypeCode,
			"content":  q.Content,
			"options":  q.Options,
			"submit":   submitMap,
		},
	}, nil
}

// xxtWorkSubmitInput is the host-supplied payload for action="work".
type xxtWorkSubmitInput struct {
	Submit  xxtmobile.WorkSubmitEntity `json:"submit"`
	Options []string                   `json:"options"`
	Answers []string                   `json:"answers"`
}

// xxtSubmitWork (action="work") submits one question's host answers. isSubmit=true
// finalizes the whole work (tempSave=false); default saves (tempSave=true).
func xxtSubmitWork(sess SessionData, input TaskInput) (RunTaskResult, error) {
	in, err := parseXxtWorkSubmit(input.Options["question"])
	if err != nil {
		return RunTaskResult{}, err
	}
	if in.Submit.QuestionId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=work requires options.question.submit.questionId")
	}
	isSubmit := optBool(input.Options["isSubmit"], false)
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: "dry_run",
			Message: fmt.Sprintf("action=work isSubmit=%v answers=%d", isSubmit, len(in.Answers))}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtWorkSubmitProvider(c, &in.Submit, in.Options, in.Answers, !isSubmit)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work submit failed: %w", err)
	}
	var resp struct {
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
	}
	_ = json.Unmarshal([]byte(raw), &resp)
	if !resp.Status {
		return RunTaskResult{}, fmt.Errorf("xuexitong: work submit rejected: %s", raw)
	}
	status := "saved"
	if isSubmit {
		status = "submitted"
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: in.Submit.QuestionId, Status: status,
		Message: resp.Msg,
		Raw:     map[string]interface{}{"isSubmit": isSubmit, "message": resp.Msg},
	}, nil
}

func parseXxtWorkSubmit(v interface{}) (xxtWorkSubmitInput, error) {
	if v == nil {
		return xxtWorkSubmitInput{}, fmt.Errorf("xuexitong: options.question is required ({submit,options,answers})")
	}
	b, err := json.Marshal(v)
	if err != nil {
		return xxtWorkSubmitInput{}, fmt.Errorf("xuexitong: options.question marshal error: %w", err)
	}
	var out xxtWorkSubmitInput
	if err := json.Unmarshal(b, &out); err != nil {
		return xxtWorkSubmitInput{}, fmt.Errorf("xuexitong: options.question parse error: %w", err)
	}
	return out, nil
}
