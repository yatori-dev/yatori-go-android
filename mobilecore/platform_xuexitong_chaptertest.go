package mobilecore

// 学习通章测答题

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image/png"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

// --- providers (swap in tests) ---

var xxtChapterFetchProvider = func(c *xxtmobile.XxtClient, courseId, classId, knowledgeId, cpi, workId, schoolId, jobId, ktoken, enc string) (string, error) {
	return c.WorkFetchQuestionApi(courseId, classId, knowledgeId, cpi, workId, schoolId, jobId, ktoken, enc)
}
var xxtChapterSubmitProvider = func(c *xxtmobile.XxtClient, meta *xxtmobile.ChapterTestMeta, answers []xxtmobile.ChapterTestAnswer, isSubmit bool) (string, error) {
	return c.WorkNewSubmitAnswerApi(meta, answers, isSubmit)
}
var xxtChapterCaptchaImgProvider = func(c *xxtmobile.XxtClient) (string, error) {
	img, err := c.ChapterCaptchaImageApi()
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(buf.Bytes()), nil
}
var xxtChapterCaptchaPassProvider = func(c *xxtmobile.XxtClient, code string) (bool, error) {
	return c.PassChapterCaptchaApi(code)
}
var xxtAIProvider = func(c *xxtmobile.XxtClient, classId, courseId, cpi, content string) (string, error) {
	return c.XueXiTongAIAggregation(classId, courseId, cpi, content)
}

// xxtPullChapterTest (action="pullChapterTest") pulls the whole chapter-test paper:
// raw.meta (submit ctx) + raw.questions[].
func xxtPullChapterTest(sess SessionData, input TaskInput) (RunTaskResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	cpi := strOf(input.Raw["cpi"])
	workId := strOf(input.Raw["workId"])
	schoolId := strOf(input.Raw["schoolId"])
	jobId := strOf(input.Raw["jobId"])
	ktoken := strOf(input.Raw["ktoken"])
	enc := strOf(input.Raw["enc"])
	if courseId == "" || classId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=pullChapterTest requires raw.courseId, classId")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: workId, Status: "dry_run", Message: "action=pullChapterTest"}, nil
	}
	c := xxtClient(sess)
	// ktoken/enc are the paper-fetch auth tokens and live ONLY on the knowledge card, not in
	// the task list. A quiz task carries workId but never ktoken/enc, so fetch the card
	// whenever either is missing (not just when workId is unknown) — otherwise the paper
	// fetch is unauthorized and chaoxing returns an empty error page, yielding 0 questions
	// and an empty submit the server rejects with "无效的参数：code-1".
	if workId == "" || ktoken == "" || enc == "" {
		if knowledgeId == "" || cpi == "" {
			return RunTaskResult{}, fmt.Errorf("xuexitong: action=pullChapterTest requires raw.knowledgeId and cpi to prepare missing card fields")
		}
		cardHTML, err := xxtCardProvider(c, classId, courseId, knowledgeId, cpi)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test card fetch failed: %w", err)
		}
		meta, err := xxtmobile.ParseCardHTMLForChapterTest(cardHTML, workId)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test card parse failed: %w", err)
		}
		if workId == "" {
			workId = meta.WorkID
		}
		if schoolId == "" {
			schoolId = meta.SchoolID
		}
		if jobId == "" {
			jobId = meta.JobID
		}
		if ktoken == "" {
			ktoken = meta.KToken
		}
		if enc == "" {
			enc = meta.Enc
		}
		// defaults.userid from the card is the paper-fetch userid (go-core's PUID). Backfill it
		// onto the client when the session lacked it and the UID cookie wasn't present either.
		if c.UserID == "" && meta.PUID != "" {
			c.UserID = meta.PUID
		}
	}
	if workId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=pullChapterTest requires workId")
	}
	raw, err := xxtChapterFetchProvider(c, courseId, classId, knowledgeId, cpi, workId, schoolId, jobId, ktoken, enc)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test fetch failed: %w", err)
	}
	meta, err := xxtmobile.ParseChapterTestMeta(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	// The work HTML doesn't always carry courseId/classId (and cpi/knowledgeId) inputs; the node
	// context (raw) is the authoritative source — the same provenance the original console relies
	// on, and this action already required raw.courseId/classId above. Backfill so the later
	// chapterTest submit has them (otherwise it errors "requires options.paper.meta").
	if meta.CourseId == "" {
		meta.CourseId = courseId
	}
	if meta.ClassId == "" {
		meta.ClassId = classId
	}
	if meta.Cpi == "" {
		meta.Cpi = cpi
	}
	if meta.Knowledgeid == "" {
		meta.Knowledgeid = knowledgeId
	}
	questions, err := xxtmobile.ParseChapterTestQuestions(raw)
	if err != nil {
		return RunTaskResult{}, err
	}
	// A real paper always has questions. Zero means the fetch returned a non-paper page
	// (auth rejected, redirect to login, permission wall). Fail loudly with the fetch inputs,
	// the redirect landing URL, and a page snippet instead of silently proceeding to an empty
	// submit the server rejects as "无效的参数".
	if len(questions) == 0 {
		snip := raw
		if len(snip) > 700 {
			snip = snip[:700]
		}
		return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test fetch returned 0 questions. inputs{workId=%q schoolId=%q jobId=%q ktokenLen=%d encLen=%d userId=%q} finalURL=%q rawLen=%d snippet=%q",
			workId, schoolId, jobId, len(ktoken), len(enc), c.UserID, c.LastWorkFetchURL, len(raw), snip)
	}
	metaMap := map[string]interface{}{}
	mb, _ := json.Marshal(meta)
	_ = json.Unmarshal(mb, &metaMap)
	qs := make([]map[string]interface{}, 0, len(questions))
	for _, q := range questions {
		qs = append(qs, map[string]interface{}{
			"qid": q.Qid, "type": q.Type, "typeCode": q.TypeCode, "content": q.Content,
			"options": q.Options, "selects": q.Selects, "blanks": q.Blanks,
		})
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: workId, Status: "questions",
		Message: fmt.Sprintf("questions=%d", len(qs)),
		Raw:     map[string]interface{}{"meta": metaMap, "questions": qs},
	}, nil
}

// xxtChapterTestInput is the host-supplied payload for action="chapterTest".
type xxtChapterTestInput struct {
	Meta    xxtmobile.ChapterTestMeta     `json:"meta"`
	Answers []xxtmobile.ChapterTestAnswer `json:"answers"`
}

// xxtSubmitChapterTest (action="chapterTest") submits the whole paper. isSubmit=true
// finalizes (pyFlag=""); default saves (pyFlag="1"). On a character captcha, returns
// status=captcha + raw.captchaImage (base64 PNG) for the host to OCR.
func xxtSubmitChapterTest(sess SessionData, input TaskInput) (RunTaskResult, error) {
	in, err := parseXxtChapterTest(input.Options["paper"])
	if err != nil {
		return RunTaskResult{}, err
	}
	if in.Meta.CourseId == "" || in.Meta.ClassId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=chapterTest requires options.paper.meta (courseId/classId)")
	}
	isSubmit := optBool(input.Options["isSubmit"], false)
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: in.Meta.WorkRelationId, Status: "dry_run",
			Message: fmt.Sprintf("action=chapterTest isSubmit=%v answers=%d", isSubmit, len(in.Answers))}, nil
	}
	c := xxtClient(sess)
	raw, err := xxtChapterSubmitProvider(c, &in.Meta, in.Answers, isSubmit)
	if err != nil {
		if errors.Is(err, xxtmobile.ErrChapterCaptcha) {
			imgB64, ierr := xxtChapterCaptchaImgProvider(c)
			if ierr != nil {
				return RunTaskResult{}, fmt.Errorf("xuexitong: captcha image fetch failed: %w", ierr)
			}
			return RunTaskResult{
				Platform: "xuexitong", TaskID: in.Meta.WorkRelationId, Status: "captcha",
				Message: "字符验证码 — 宿主 OCR 后调 passChapterCaptcha 再重发 chapterTest",
				Raw:     map[string]interface{}{"captchaImage": imgB64},
			}, nil
		}
		return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test submit failed: %w", err)
	}
	// Validate the server's actual verdict. The submit endpoint returns
	// {"status":true,"msg":"success!"} on accept and {"status":false,"msg":"作业提交失败！"}
	// on reject. Without this we would report "submitted" for a paper the server refused —
	// the sibling xxtSubmitWork and the console reference both gate on this field.
	var resp struct {
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
	}
	_ = json.Unmarshal([]byte(raw), &resp)
	if !resp.Status {
		return RunTaskResult{}, fmt.Errorf("xuexitong: chapter-test submit rejected: %s | %s", raw, chapterSubmitDiag(&in.Meta, in.Answers, isSubmit))
	}
	status := "saved"
	if isSubmit {
		status = "submitted"
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: in.Meta.WorkRelationId, Status: status,
		Message: fmt.Sprintf("isSubmit=%v msg=%s", isSubmit, resp.Msg),
		Raw:     map[string]interface{}{"isSubmit": isSubmit, "raw": raw, "msg": resp.Msg},
	}, nil
}

// chapterSubmitDiag builds a compact one-line diagnostic for a rejected submit: which
// submit-context meta fields came through empty, and how many of the paper's answers
// actually carried content. It pinpoints whether a rejection is an upstream fetch/parse
// problem (answers=0 or core meta empty) or a structural one (all present but refused).
func chapterSubmitDiag(meta *xxtmobile.ChapterTestMeta, answers []xxtmobile.ChapterTestAnswer, isSubmit bool) string {
	var empty []string
	chk := func(name, v string) {
		if v == "" {
			empty = append(empty, name)
		}
	}
	chk("courseId", meta.CourseId)
	chk("classId", meta.ClassId)
	chk("encWork", meta.EncWork)
	chk("totalQuestionNum", meta.TotalQuestionNum)
	chk("answerId", meta.AnswerId)
	chk("workAnswerId", meta.WorkAnswerId)
	chk("api", meta.Api)
	chk("workRelationId", meta.WorkRelationId)
	chk("jobId", meta.JobId)
	chk("cpi", meta.Cpi)
	chk("knowledgeid", meta.Knowledgeid)
	chk("fullScore", meta.FullScore)
	withAns := 0
	for _, a := range answers {
		if len(a.Answers) > 0 {
			withAns++
		}
	}
	return fmt.Sprintf("diag isSubmit=%v answers=%d/%d emptyMeta=%v", isSubmit, withAns, len(answers), empty)
}

// xxtPassChapterCaptcha (action="passChapterCaptcha") submits the host's OCR result.
func xxtPassChapterCaptcha(sess SessionData, input TaskInput) (RunTaskResult, error) {
	code := strOf(input.Options["code"])
	if code == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=passChapterCaptcha requires options.code (the OCR'd captcha text)")
	}
	c := xxtClient(sess)
	ok, err := xxtChapterCaptchaPassProvider(c, code)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: pass captcha failed: %w", err)
	}
	if !ok {
		return RunTaskResult{}, fmt.Errorf("xuexitong: captcha code rejected")
	}
	return RunTaskResult{
		Platform: "xuexitong", TaskID: "captcha", Status: "done",
		Message: "captcha passed; re-send chapterTest",
		Raw:     map[string]interface{}{"passed": true},
	}, nil
}

// xxtRunAI (action="xxtAI") runs the built-in Coze AI for one question. The host
// supplies options.content (its own assembled question prompt); returns raw.answer
// (the AI's answer text, usually a JSON array string).
func xxtRunAI(sess SessionData, input TaskInput) (RunTaskResult, error) {
	classId := strOf(input.Raw["classId"])
	courseId := strOf(input.Raw["courseId"])
	cpi := strOf(input.Raw["cpi"])
	content := strOf(input.Options["content"])
	if classId == "" || courseId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=xxtAI requires raw.classId and raw.courseId")
	}
	if content == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=xxtAI requires options.content (the question prompt)")
	}
	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "xuexitong", TaskID: courseId, Status: "dry_run", Message: "action=xxtAI"}, nil
	}
	c := xxtClient(sess)
	answer, err := xxtAIProvider(c, classId, courseId, cpi, content)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: built-in AI failed: %w", err)
	}
	// best-effort: surface a parsed []string if the AI returned JSON
	var arr []string
	_ = json.Unmarshal([]byte(answer), &arr)
	return RunTaskResult{
		Platform: "xuexitong", TaskID: courseId, Status: "done",
		Message: "xxtAI answered",
		Raw:     map[string]interface{}{"answer": answer, "answers": arr},
	}, nil
}

func parseXxtChapterTest(v interface{}) (xxtChapterTestInput, error) {
	if v == nil {
		return xxtChapterTestInput{}, fmt.Errorf("xuexitong: options.paper is required ({meta,answers})")
	}
	b, err := json.Marshal(v)
	if err != nil {
		return xxtChapterTestInput{}, fmt.Errorf("xuexitong: options.paper marshal error: %w", err)
	}
	var out xxtChapterTestInput
	if err := json.Unmarshal(b, &out); err != nil {
		return xxtChapterTestInput{}, fmt.Errorf("xuexitong: options.paper parse error: %w", err)
	}
	return out, nil
}
