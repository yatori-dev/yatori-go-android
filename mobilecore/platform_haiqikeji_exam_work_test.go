package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	haiqikejiApi "github.com/yatori-dev/yatori-go-mobile-core/api/haiqikeji"
)

// --- fakes for the exam/work providers ---

func fakeHqkjWorkList(raw string, err error) func() {
	orig := hqkjWorkListProvider
	hqkjWorkListProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _ string) (string, error) { return raw, err }
	return func() { hqkjWorkListProvider = orig }
}
func fakeHqkjExamList(raw string, err error) func() {
	orig := hqkjExamListProvider
	hqkjExamListProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _ string) (string, error) { return raw, err }
	return func() { hqkjExamListProvider = orig }
}
func fakeHqkjWorkDetail(raw string, err error) func() {
	orig := hqkjWorkDetailProvider
	hqkjWorkDetailProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _, _ string) (string, error) { return raw, err }
	return func() { hqkjWorkDetailProvider = orig }
}
func fakeHqkjWorkStart(raw string, err error) func() {
	orig := hqkjWorkStartProvider
	hqkjWorkStartProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _, _, _ string) (string, error) { return raw, err }
	return func() { hqkjWorkStartProvider = orig }
}
func fakeHqkjWorkConsult(raw string, err error) func() {
	orig := hqkjWorkConsultProvider
	hqkjWorkConsultProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _ string) (string, error) { return raw, err }
	return func() { hqkjWorkConsultProvider = orig }
}
func fakeHqkjExamDetail(raw string, err error) func() {
	orig := hqkjExamDetailProvider
	hqkjExamDetailProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _, _ string) (string, error) { return raw, err }
	return func() { hqkjExamDetailProvider = orig }
}
func fakeHqkjExamStart(raw string, err error) func() {
	orig := hqkjExamStartProvider
	hqkjExamStartProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _, _, _ string) (string, error) { return raw, err }
	return func() { hqkjExamStartProvider = orig }
}

// captureHqkjWorkAnswer records each work-answer submit and returns a code-200 ok.
func captureHqkjWorkAnswer(calls *[]map[string]string) func() {
	orig := hqkjWorkAnswerProvider
	hqkjWorkAnswerProvider = func(_ *haiqikejiApi.HqkjUserCache, courseID, workID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
		*calls = append(*calls, map[string]string{
			"workId": workID, "topicId": topicID, "recordId": recordID,
			"wrId": wrID, "waId": waID, "type": qType, "answer": strings.Join(answers, ","),
		})
		return `{"code":200,"msg":"提交成功"}`, nil
	}
	return func() { hqkjWorkAnswerProvider = orig }
}

const hqkjEwSessJSON = `{"platform":"haiqikeji","token":"tok","extra":{"userId":"u1","schoolId":"s1","preUrl":"http://x"}}`

const hqkjWorkListRaw = `{"code":200,"data":{"workInfo":[{"workId":1011,"title":"作业一"}]}}`
const hqkjExamListRaw = `{"code":200,"data":{"examInfo":[{"examId":2022,"title":"期末考试"}]}}`
const hqkjWorkDetailRaw = `{"code":200,"data":{"workDetail":[{"id":501,"paperId":6196,"classId":1016378}]}}`
const hqkjWorkStartRaw = `{"code":200,"data":{"workTopics":[{"id":7001,"recordId":88,"type":1,"topic":"<p>中国的首都是？</p>","option":[{"idx":"A","answer":"北京"},{"idx":"B","answer":"上海"}]}]}}`

// --- pullWork / pullExam ---

func TestRunTaskHaiqikeji_PullWork(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeHqkjWorkList(hqkjWorkListRaw, nil)
	defer restore()

	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"},"options":{"action":"pullWork"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullWork should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	works, ok := res.Raw["works"].([]interface{})
	if !ok || len(works) != 1 {
		t.Fatalf("expected 1 work, got %v", res.Raw["works"])
	}
	w := works[0].(map[string]interface{})
	if w["workId"] != "1011" || w["courseId"] != "c1" || w["nodeId"] != "node-1" {
		t.Fatalf("unexpected work: %v", w)
	}
}

func TestRunTaskHaiqikeji_PullExam(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeHqkjExamList(hqkjExamListRaw, nil)
	defer restore()

	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"},"options":{"action":"pullExam"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullExam should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	exams, ok := res.Raw["exams"].([]interface{})
	if !ok || len(exams) != 1 {
		t.Fatalf("expected 1 exam, got %v", res.Raw["exams"])
	}
	if exams[0].(map[string]interface{})["examId"] != "2022" {
		t.Fatalf("unexpected exam: %v", exams[0])
	}
}

func TestRunTaskHaiqikeji_PullWork_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{},"options":{"action":"pullWork"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if e.OK {
		t.Fatal("pullWork without courseId should fail")
	}
}

// --- workQuestions (start path) ---

func TestRunTaskHaiqikeji_WorkQuestions(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeHqkjWorkDetail(hqkjWorkDetailRaw, nil)()
	defer fakeHqkjWorkStart(hqkjWorkStartRaw, nil)()

	taskJSON := `{"platform":"haiqikeji","id":"1011","raw":{"courseId":"c1","nodeId":"node-1","title":"作业一"},"options":{"action":"workQuestions"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("workQuestions should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "questions" {
		t.Fatalf("status=%q want questions", res.Status)
	}
	qs, ok := res.Raw["questions"].([]interface{})
	if !ok || len(qs) != 1 {
		t.Fatalf("expected 1 question, got %v", res.Raw["questions"])
	}
	q := qs[0].(map[string]interface{})
	if q["topicId"] != "7001" || q["content"] != "中国的首都是？" {
		t.Fatalf("unexpected question: %v", q)
	}
	opts, _ := q["options"].([]interface{})
	idx, _ := q["optionIdx"].([]interface{})
	if len(opts) != 2 || len(idx) != 2 || idx[1] != "B" {
		t.Fatalf("options/optionIdx mismatch: opts=%v idx=%v", opts, idx)
	}
}

// when work_start yields no questions, the platform falls back to consult.
func TestRunTaskHaiqikeji_WorkQuestions_ConsultFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeHqkjWorkDetail(hqkjWorkDetailRaw, nil)()
	defer fakeHqkjWorkStart(`{"code":200,"data":{"workTopics":[]}}`, nil)() // start empty
	consult := `{"code":200,"data":{"workResult":[{"topicId":9001,"wrId":42,"waId":7,"type":1,"topic":"题干","option":[{"idx":"A","answer":"甲"},{"idx":"B","answer":"乙"}]}]}}`
	defer fakeHqkjWorkConsult(consult, nil)()

	taskJSON := `{"platform":"haiqikeji","id":"1011","raw":{"courseId":"c1","nodeId":"node-1"},"options":{"action":"workQuestions"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("consult fallback should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	qs, _ := res.Raw["questions"].([]interface{})
	if len(qs) != 1 {
		t.Fatalf("expected 1 question via consult, got %v", res.Raw["questions"])
	}
	if qs[0].(map[string]interface{})["topicId"] != "9001" {
		t.Fatalf("expected consult topicId 9001, got %v", qs[0])
	}
}

// --- work submit (real submit by default) ---

func TestRunTaskHaiqikeji_WorkSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var calls []map[string]string
	defer captureHqkjWorkAnswer(&calls)()

	answers := `[{"topicId":"7001","recordId":"88","wrId":"88","waId":"0","type":1,"options":["北京","上海"],"optionIdx":["A","B"],"answers":["上海"]}]`
	taskJSON := `{"platform":"haiqikeji","id":"1011","raw":{"courseId":"c1"},"options":{"action":"work","answers":` + answers + `}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("work submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q want submitted", res.Status)
	}
	if len(calls) != 1 {
		t.Fatalf("expected 1 submit call, got %d", len(calls))
	}
	// host text "上海" must be mapped to server idx "B"
	if calls[0]["answer"] != "B" {
		t.Fatalf("answer wire=%q want B", calls[0]["answer"])
	}
	if calls[0]["topicId"] != "7001" || calls[0]["type"] != "1" {
		t.Fatalf("unexpected call fields: %v", calls[0])
	}
}

func TestRunTaskHaiqikeji_WorkSubmit_MissingAnswers(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"haiqikeji","id":"1011","raw":{"courseId":"c1"},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if e.OK {
		t.Fatal("work without answers should fail")
	}
}

// --- exam: dry-run by default, real submit only when realSubmit=true ---

func TestRunTaskHaiqikeji_ExamDryRunDefault(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// exam-answer provider must NOT be called on default dry-run; error proves it.
	orig := hqkjExamAnswerProvider
	hqkjExamAnswerProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _, _, _, _, _, _ string, _ []string) (string, error) {
		return "", errors.New("exam submit must not be called in dry-run")
	}
	defer func() { hqkjExamAnswerProvider = orig }()

	answers := `[{"topicId":"5001","recordId":"33","type":1,"options":["甲","乙"],"optionIdx":["A","B"],"answers":["乙"]}]`
	taskJSON := `{"platform":"haiqikeji","id":"2022","raw":{"courseId":"c1"},"options":{"action":"exam","answers":` + answers + `}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("status=%q want dry_run", res.Status)
	}
	if res.Raw["realSubmit"] != false {
		t.Fatalf("raw.realSubmit=%v want false", res.Raw["realSubmit"])
	}
}

func TestRunTaskHaiqikeji_ExamRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var calls []map[string]string
	orig := hqkjExamAnswerProvider
	hqkjExamAnswerProvider = func(_ *haiqikejiApi.HqkjUserCache, courseID, examID, topicID, recordID, wrID, waID, qType string, answers []string) (string, error) {
		calls = append(calls, map[string]string{"examId": examID, "topicId": topicID, "type": qType, "answer": strings.Join(answers, ",")})
		return `{"code":200,"msg":"提交成功"}`, nil
	}
	defer func() { hqkjExamAnswerProvider = orig }()

	answers := `[{"topicId":"5001","recordId":"33","type":1,"options":["甲","乙"],"optionIdx":["A","B"],"answers":["乙"]}]`
	taskJSON := `{"platform":"haiqikeji","id":"2022","raw":{"courseId":"c1"},"options":{"action":"exam","realSubmit":true,"answers":` + answers + `}}`
	e := parseEnvelope(t, RunTask(hqkjEwSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam realSubmit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q want submitted", res.Status)
	}
	if len(calls) != 1 || calls[0]["answer"] != "B" {
		t.Fatalf("unexpected exam submit calls: %v", calls)
	}
}
