package mobile

import "testing"

const chapterPaperHTML = `
<input id="courseId" value="C1">
<input id="classId" value="CL1">
<input id="knowledgeid" value="K1">
<input id="cpi" value="9">
<input id="jobid" value="J1">
<input id="totalQuestionNum" value="3">
<input id="answerId" value="A1">
<input id="workAnswerId" value="WA1">
<input id="api" value="API1">
<input id="fullScore" value="100">
<input id="oldSchoolId" value="OS1">
<input id="oldWorkId" value="OW1">
<input id="workRelationId" value="WR1">
<input id="enc_work" value="EW1">
<div class="chapter-title" workname="第一章测验"></div>
<div class="Py-mian1" data="q1">
  <div class="Py-m1-title"><span class="quesType">1.[单选题]</span><div class="workTextWrap"><p>中国的首都是？</p></div></div>
  <ul class="answerList singleChoice">
    <li><em class="choose-opt">A</em><cc>北京</cc></li>
    <li><em class="choose-opt">B</em><cc>上海</cc></li>
  </ul>
</div>
<div class="Py-mian1" data="q2">
  <div class="Py-m1-title"><span class="quesType">2.[判断题]</span><div class="workTextWrap"><p>地球是圆的</p></div></div>
  <ul class="answerList panduan">
    <li val-param="true"><em>A</em><p>对</p></li>
    <li val-param="false"><em>B</em><p>错</p></li>
  </ul>
</div>
<div class="Py-mian1" data="q3">
  <div class="Py-m1-title"><span class="quesType">3.[填空题]</span><div class="workTextWrap"><p>1+__=2, 2+__=4</p></div></div>
  <ul class="blankList2"><p>填空1:填空2:</p></ul>
</div>`

func TestParseChapterTestMeta(t *testing.T) {
	meta, err := ParseChapterTestMeta(chapterPaperHTML)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if meta.CourseId != "C1" || meta.ClassId != "CL1" || meta.TotalQuestionNum != "3" {
		t.Fatalf("base meta wrong: %+v", meta)
	}
	if meta.EncWork != "EW1" || meta.WorkRelationId != "WR1" || meta.Api != "API1" {
		t.Fatalf("submit ids wrong: %+v", meta)
	}
	if meta.Title != "第一章测验" {
		t.Fatalf("title=%q", meta.Title)
	}
}

func TestParseChapterTestQuestions(t *testing.T) {
	qs, err := ParseChapterTestQuestions(chapterPaperHTML)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(qs) != 3 {
		t.Fatalf("want 3 questions, got %d", len(qs))
	}
	// single
	if qs[0].Type != XxtQTypeSingle || qs[0].TypeCode != "0" || qs[0].Content != "中国的首都是？" {
		t.Fatalf("q0=%+v", qs[0])
	}
	if qs[0].Options["A"] != "北京" || qs[0].Options["B"] != "上海" {
		t.Fatalf("q0 options=%v", qs[0].Options)
	}
	// judge
	if qs[1].Type != XxtQTypeJudge || qs[1].TypeCode != "3" {
		t.Fatalf("q1=%+v", qs[1])
	}
	// fill: 2 blanks
	if qs[2].Type != XxtQTypeFill || qs[2].TypeCode != "2" || qs[2].Blanks != 2 {
		t.Fatalf("q2=%+v (blanks=%d)", qs[2], qs[2].Blanks)
	}
}

func TestParseChapterTestQuestions_Expired(t *testing.T) {
	if _, err := ParseChapterTestQuestions(`<div class="chapter-content">该作业已截止，不能作答</div>`); err == nil {
		t.Fatal("expired paper must error")
	}
}

func TestChapterTestSubmitForm_Fields(t *testing.T) {
	meta := &ChapterTestMeta{CourseId: "C1", ClassId: "CL1", TotalQuestionNum: "2", EncWork: "EW1", WorkRelationId: "WR1", Api: "API1", AnswerId: "A1", WorkAnswerId: "WA1", JobId: "J1", Knowledgeid: "K1", FullScore: "100"}
	answers := []ChapterTestAnswer{
		{Qid: "q1", TypeCode: "0", Options: []string{"北京", "上海"}, Answers: []string{"上海"}},
		{Qid: "q2", TypeCode: "3", Answers: []string{"正确"}},
	}
	body, contentType := ChapterTestSubmitForm("U1", meta, answers, true)
	s := string(body)
	if contentType == "" {
		t.Fatal("contentType empty")
	}
	// pyFlag empty = submit
	mustContain(t, s, `name="pyFlag"`)
	// single q1 → letter B
	mustContain(t, s, `name="answerq1"`)
	mustContain(t, s, "B")
	mustContain(t, s, `name="answertypeq1"`)
	// judge q2 → true
	mustContain(t, s, `name="answerq2"`)
	mustContain(t, s, "true")
	// answerwqbid lists both qids
	mustContain(t, s, "q1,q2,")
}

func TestChapterTestSubmitForm_FillAndSave(t *testing.T) {
	meta := &ChapterTestMeta{CourseId: "C1", ClassId: "CL1"}
	answers := []ChapterTestAnswer{{Qid: "q3", TypeCode: "2", Answers: []string{"1", "3"}}}
	body, _ := ChapterTestSubmitForm("U1", meta, answers, false) // save
	s := string(body)
	mustContain(t, s, `name="answerq31"`)
	mustContain(t, s, `name="answerq32"`)
	mustContain(t, s, `name="tiankongsizeq3"`)
}

func TestParseHTMLValues(t *testing.T) {
	html := `<input id="cozeEnc" value="ENC123"><input id="userId" value="U9"><input value="V2" id="courseId">`
	m := parseHTMLValues(html)
	if m["cozeEnc"] != "ENC123" || m["userId"] != "U9" {
		t.Fatalf("id-before-value parse wrong: %v", m)
	}
	if m["courseId"] != "V2" {
		t.Fatalf("value-before-id parse wrong: %v", m)
	}
}

func mustContain(t *testing.T, haystack, needle string) {
	t.Helper()
	if !contains(haystack, needle) {
		t.Fatalf("expected body to contain %q", needle)
	}
}

func contains(h, n string) bool {
	for i := 0; i+len(n) <= len(h); i++ {
		if h[i:i+len(n)] == n {
			return true
		}
	}
	return false
}
