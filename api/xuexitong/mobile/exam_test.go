package mobile

import (
	"net/url"
	"testing"
)

const examSingleHTML = `
<input type="hidden" id="questionId" value="Q9">
<input type="hidden" name="typeQ9" value="0">
<input type="hidden" name="typeNameQ9" value="单选题">
<input type="hidden" id="courseId" value="C1">
<input type="hidden" id="testPaperId" value="TP1">
<input type="hidden" id="testUserRelationId" value="UR1">
<input type="hidden" id="classId" value="CL1">
<input type="hidden" id="cpi" value="9">
<input type="hidden" id="enc" value="E">
<input type="hidden" id="encRemainTime" value="ER">
<input type="hidden" id="encLastUpdateTime" value="EL">
<input type="hidden" id="userId" value="U1">
<input type="hidden" id="enterPageTime" value="123">
<input type="hidden" id="remainTime" value="600">
<input type="hidden" name="scoreQ9" value="4">
<div class="questionWrap" data="Q9">
  <div class="tit"><p>1+1=?</p></div>
  <div class="singleChoice" name="A"><div class="answerInfo"><cc>2</cc></div></div>
  <div class="singleChoice" name="B"><div class="answerInfo"><cc>3</cc></div></div>
</div>`

func TestParseExamList(t *testing.T) {
	html := `<ul class="nav"><li data="/exam?taskrefId=E1&courseId=C1&userId=U1&clazzId=CL1&type=2&enc_task=ET&msgId=0"><div><p>期末考试</p><span>待做</span><span>60分钟</span></div></li></ul>`
	items, err := ParseExamList(html)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(items) != 1 || items[0].TaskRefId != "E1" || items[0].Name != "期末考试" || items[0].RemainTime != "60分钟" {
		t.Fatalf("unexpected exam item: %+v", items)
	}
}

func TestParseExamEnter(t *testing.T) {
	html := `<div>本试卷共 5 题</div><input type="hidden" id="captchaCaptchaId" value="CID"><input type="hidden" id="testPaperId" value="TP1"><input type="hidden" id="testUserRelationId" value="UR1"><input type="hidden" id="cpi" value="9">`
	info, err := ParseExamEnter(html)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.QuestionTotal != 5 || info.CaptchaCaptchaId != "CID" || info.ExamRelationId != "TP1" || info.AnswerId != "UR1" || info.Cpi != "9" {
		t.Fatalf("unexpected enter info: %+v", info)
	}
}

func TestParseExamQuestion_Single(t *testing.T) {
	q, err := ParseExamQuestion(examSingleHTML)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if q.Type != XxtQTypeSingle || q.TypeCode != "0" {
		t.Fatalf("type=%q code=%q", q.Type, q.TypeCode)
	}
	if q.Content != "1+1=?" {
		t.Fatalf("content=%q", q.Content)
	}
	if len(q.Options) != 2 || q.Options[0] != "A2" || q.Options[1] != "B3" {
		t.Fatalf("options=%v", q.Options)
	}
	if q.Submit.TestPaperId != "TP1" || q.Submit.TestUserRelationId != "UR1" || q.Submit.Score != "4" || q.Submit.QuestionId != "Q9" || q.Submit.TypeName != "单选题" {
		t.Fatalf("submit=%+v", q.Submit)
	}
}

func TestExamAnswerForm_Single(t *testing.T) {
	e := &ExamSubmitEntity{CourseId: "C1", TestPaperId: "TP1", TestUserRelationId: "UR1", ClassId: "CL1", QuestionId: "Q9", TypeCode: "0", TypeName: "单选题", Score: "4", UserId: "U1"}
	v := ExamAnswerForm(e, []string{"A2", "B3"}, []string{"3"}, true)
	if v.Get("answerQ9") != "B" {
		t.Fatalf("answerQ9=%q want B", v.Get("answerQ9"))
	}
	if v.Get("typeQ9") != "0" || v.Get("typeNameQ9") != "单选题" {
		t.Fatalf("type/typeName wrong")
	}
	if v.Get("tempSave") != "true" || v.Get("testPaperId") != "TP1" {
		t.Fatalf("base fields wrong")
	}
}

func TestExamAnswerForm_Fill(t *testing.T) {
	e := &ExamSubmitEntity{QuestionId: "Q2", TypeCode: "2", TypeName: "填空题"}
	v := ExamAnswerForm(e, nil, []string{"答案1", "答案2"}, false)
	if v.Get("answerEditorQ21") != "答案1" || v.Get("answerEditorQ22") != "答案2" {
		t.Fatalf("fill editor fields wrong: %q %q", v.Get("answerEditorQ21"), v.Get("answerEditorQ22"))
	}
	if v.Get("blankNumQ2") != "1,2," {
		t.Fatalf("blankNumQ2=%q", v.Get("blankNumQ2"))
	}
}

func TestGetExamSignature(t *testing.T) {
	sig := GetExamSignature("346635955", "885532434", 950, 150)
	if sig["value"].(string) != "(950|150)" {
		t.Fatalf("value=%q want (950|150)", sig["value"])
	}
	if sig["pos"].(string) == "" {
		t.Fatal("pos should be non-empty")
	}
	if sig["_edt"].(string) == "" {
		t.Fatal("_edt should be non-empty")
	}
	if rd, ok := sig["rd"].(float64); !ok || rd < 0 || rd >= 1 {
		t.Fatalf("rd=%v should be in [0,1)", sig["rd"])
	}
}
func TestExamSubmitReferer(t *testing.T) {
	e := &ExamSubmitEntity{
		CourseId: "C1", ClassId: "CL1", Cpi: "9", Tid: "T1", AnswerId: "A1",
		RemainTimeParam: "R1", Enc: "ENC1",
	}
	u, err := url.Parse(examSubmitReferer(e, 123456789))
	if err != nil {
		t.Fatalf("parse referer: %v", err)
	}
	q := u.Query()
	for key, want := range map[string]string{
		"courseId": "C1", "classId": "CL1", "cpi": "9", "tId": "T1", "id": "A1",
		"remainTimeParam": "R1", "enc": "ENC1", "relationAnswerLastUpdateTime": "123456789",
	} {
		if got := q.Get(key); got != want {
			t.Errorf("%s = %q, want %q", key, got, want)
		}
	}
}
