package mobile

import (
	"image"
	"image/color"
	"testing"
)

const workSingleHTML = `
<input type="hidden" id="questionId" value="Q1">
<input type="hidden" name="typeQ1" value="0">
<input type="hidden" id="courseId" value="C1">
<input type="hidden" id="classId" value="CL1">
<input type="hidden" id="workId" value="W1">
<input type="hidden" id="enc" value="ENC">
<input type="hidden" id="source" value="0">
<input type="hidden" id="encWork" value="EW">
<input type="hidden" id="index" value="0">
<input type="hidden" name="scoreQ1" value="5.0">
<input type="hidden" id="testUserRelationId" value="AID">
<div class="singleQuesId" data="Q1">
  <div class="ans-cc workWrap"><p>中国的首都是？</p></div>
  <div class="centerSpan" id="A"><p>北京</p></div>
  <div class="centerSpan" id="B"><p>上海</p></div>
  <div class="centerSpan" id="C"><p>广州</p></div>
</div>`

func TestParseWorkList(t *testing.T) {
	html := `<ul class="nav">
		<li data="/work?taskrefId=T1&courseId=C1&userId=U1&clazzId=CL1&type=2&enc_task=ET1&msgId=0">
			<div><p>第一次作业</p><span>待做</span></div>
		</li></ul>`
	items, err := ParseWorkList(html)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("want 1 work, got %d", len(items))
	}
	w := items[0]
	if w.Name != "第一次作业" || w.Status != "待做" || w.TaskRefId != "T1" || w.CourseId != "C1" || w.EncTask != "ET1" {
		t.Fatalf("unexpected work item: %+v", w)
	}
}

func TestParseWorkEnter(t *testing.T) {
	html := `<div>本次作业共包含 12 道题目</div>
		<input type="hidden" id="captchaCaptchaId" value="CID">
		<input type="hidden" id="testUserRelationId" value="AID">
		<script>var x="...cpi=999&workAnswerId=555&enc=abcd1234ef...";</script>`
	info, err := ParseWorkEnter(html)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.QuestionTotal != 12 {
		t.Fatalf("questionTotal=%d want 12", info.QuestionTotal)
	}
	if info.CaptchaCaptchaId != "CID" {
		t.Fatalf("captchaId=%q", info.CaptchaCaptchaId)
	}
	if info.Cpi != "999" || info.WorkAnswerId != "555" || info.Enc != "abcd1234ef" {
		t.Fatalf("params: %+v", info)
	}
}

func TestParseWorkQuestion_Single(t *testing.T) {
	q, err := ParseWorkQuestion(workSingleHTML)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if q.Type != XxtQTypeSingle || q.TypeCode != "0" {
		t.Fatalf("type=%q code=%q", q.Type, q.TypeCode)
	}
	if q.Content != "中国的首都是？" {
		t.Fatalf("content=%q", q.Content)
	}
	if len(q.Options) != 3 || q.Options[0] != "北京" || q.Options[2] != "广州" {
		t.Fatalf("options=%v", q.Options)
	}
	if q.Submit.QuestionId != "Q1" || q.Submit.CourseId != "C1" || q.Submit.Score != "5.0" || q.Submit.AnswerId != "AID" {
		t.Fatalf("submit=%+v", q.Submit)
	}
}

func TestParseWorkQuestion_Expired(t *testing.T) {
	if _, err := ParseWorkQuestion(`<div>已过时效，不能操作!</div>`); err == nil {
		t.Fatal("expired paper must error")
	}
}

func TestWorkAnswerForm_Single(t *testing.T) {
	e := &WorkSubmitEntity{CourseId: "C1", WorkId: "W1", ClassId: "CL1", AnswerId: "AID", QuestionId: "Q1", TypeCode: "0", Score: "5.0", Index: "0", Enc: "ENC", Source: "0", EncWork: "EW"}
	v := WorkAnswerForm(e, []string{"北京", "上海", "广州"}, []string{"上海"}, false)
	if v.Get("answerQ1") != "B" {
		t.Fatalf("answerQ1=%q want B", v.Get("answerQ1"))
	}
	if v.Get("typeQ1") != "0" || v.Get("scoreQ1") != "5.0" {
		t.Fatalf("type/score wrong: %q %q", v.Get("typeQ1"), v.Get("scoreQ1"))
	}
	if v.Get("workRelationAnswerId") != "AID" || v.Get("questionId") != "Q1" {
		t.Fatalf("ids wrong")
	}
	// courseId/workRelationId/classId are duplicated on the wire
	if len(v["courseId"]) != 2 || len(v["workRelationId"]) != 2 || len(v["classId"]) != 2 {
		t.Fatalf("expected duplicated courseId/workRelationId/classId")
	}
}

func TestWorkAnswerForm_Multi(t *testing.T) {
	e := &WorkSubmitEntity{QuestionId: "Q2", TypeCode: "1", Score: "10"}
	v := WorkAnswerForm(e, []string{"甲", "乙", "丙"}, []string{"甲", "丙"}, false)
	if v.Get("answersQ2") != "AC" {
		t.Fatalf("answersQ2=%q want AC", v.Get("answersQ2"))
	}
}

func TestWorkAnswerForm_Judge(t *testing.T) {
	e := &WorkSubmitEntity{QuestionId: "Q3", TypeCode: "3", Score: "2"}
	v := WorkAnswerForm(e, []string{"A正确", "B错误"}, []string{"正确"}, false)
	if v.Get("answerQ3") != "true" {
		t.Fatalf("answerQ3=%q want true", v.Get("answerQ3"))
	}
}

func TestWorkAnswerForm_Fill(t *testing.T) {
	e := &WorkSubmitEntity{QuestionId: "Q4", TypeCode: "2", Score: "4"}
	v := WorkAnswerForm(e, nil, []string{"答案1", "答案2"}, false)
	if v.Get("answerQ41") != "答案1" || v.Get("answerQ42") != "答案2" {
		t.Fatalf("fill answers wrong: %q %q", v.Get("answerQ41"), v.Get("answerQ42"))
	}
	if v.Get("blankNumQ4") != "1,2," {
		t.Fatalf("blankNumQ4=%q", v.Get("blankNumQ4"))
	}
}

func TestWorkAnswerForm_Essay(t *testing.T) {
	e := &WorkSubmitEntity{QuestionId: "Q6", TypeCode: "6", Score: "20"}
	v := WorkAnswerForm(e, nil, []string{"我的论述答案"}, true)
	if v.Get("answerQ6") != "我的论述答案" {
		t.Fatalf("essay answer=%q", v.Get("answerQ6"))
	}
	if v.Get("tempSave") != "true" {
		t.Fatalf("tempSave=%q want true", v.Get("tempSave"))
	}
}

func TestSimilaritySelect(t *testing.T) {
	if got := similaritySelect("上海", []string{"北京", "上海", "广州"}); got != "B" {
		t.Fatalf("got %q want B", got)
	}
	if got := similaritySelect("anything", nil); got != "A" {
		t.Fatalf("empty options fallback got %q want A", got)
	}
}

// --- slider NCC matcher ---

// TestDetectSlideOffset builds a synthetic background with a dark square patch at a
// known x, and a matching cutout template, then asserts the detected offset.
func TestDetectSlideOffset(t *testing.T) {
	const w, h, tpl = 120, 40, 12
	patchX := 60
	bg := image.NewGray(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			bg.SetGray(x, y, color.Gray{Y: 200}) // light background
		}
	}
	// dark patch (the "gap") at patchX
	for y := 10; y < 10+tpl; y++ {
		for x := patchX; x < patchX+tpl; x++ {
			bg.SetGray(x, y, color.Gray{Y: 30})
		}
	}
	// cutout template: same dark square on a light tile
	cut := image.NewGray(image.Rect(0, 0, tpl, h))
	for y := 0; y < h; y++ {
		for x := 0; x < tpl; x++ {
			cut.SetGray(x, y, color.Gray{Y: 200})
		}
	}
	for y := 10; y < 10+tpl; y++ {
		for x := 0; x < tpl; x++ {
			cut.SetGray(x, y, color.Gray{Y: 30})
		}
	}
	got := DetectSlideOffset(bg, cut)
	want := patchX - 5 // DetectSlideOffset subtracts 5
	if got < want-2 || got > want+2 {
		t.Fatalf("DetectSlideOffset=%d, want ~%d", got, want)
	}
}
