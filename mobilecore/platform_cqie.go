package mobilecore

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	cqieApi "github.com/yatori-dev/yatori-go-mobile-core/api/cqie"
	cqieinternal "github.com/yatori-dev/yatori-go-mobile-core/mobilecore/internal/cqieapi"
)

// --- replaceable providers (test seams) ---

var cqieCaptchaProvider = func(account string) (imgB64, cookie, uuidStr string, err error) {
	return cqieinternal.FetchCaptchaBase64()
}

var cqieLoginProvider = func(cache *cqieApi.CqieUserCache) (string, error) {
	return cache.LoginApi()
}

var cqieUserDetailsProvider = func(cache *cqieApi.CqieUserCache) (string, error) {
	return cache.UserDetailsApi(3, nil)
}

var cqieCoursesProvider = func(cache *cqieApi.CqieUserCache) (string, error) {
	return cache.PullCourseListApiNew(3, nil)
}

var cqieDetailProvider = func(cache *cqieApi.CqieUserCache, courseId, studentCourseId, version string) (string, error) {
	return cache.PullProgressDetailApi(courseId, studentCourseId, version, 3, nil)
}

var cqieStartVideoProvider = func(cache *cqieApi.CqieUserCache, studentCourseId, videoId, version string) (string, error) {
	return cache.GetVideoStudyIdApi(studentCourseId, videoId, version, 3, nil)
}

var cqieSaveStudyProvider = func(cache *cqieApi.CqieUserCache, courseId, studentCourseId, unitId, videoId, coursewareId, version string, startPos, stopPos int) (string, error) {
	return cache.SaveStudyTimeApi(courseId, studentCourseId, unitId, videoId, coursewareId, version, startPos, stopPos, 3, nil)
}

var cqieSaveSegmentProvider = func(cache *cqieApi.CqieUserCache, courseId, studentCourseId, unitId, videoId, coursewareId, segmentKnowledgeId, maxCurrentPos, version string, startPos, stopPos int) (string, error) {
	return cache.SaveSegmentStudyTimeApi(courseId, studentCourseId, unitId, videoId, coursewareId, segmentKnowledgeId, maxCurrentPos, version, startPos, stopPos, 3, nil)
}

var cqiePullVideoWorkProvider = func(cache *cqieApi.CqieUserCache, knowledgeNodeId, studentCourseId, unitId string) (string, error) {
	return cache.PullVideoWorkPaperApi(knowledgeNodeId, studentCourseId, unitId)
}

var cqieSubmitWorkAnswerProvider = func(cache *cqieApi.CqieUserCache, answerJSON string) (string, error) {
	return cache.SubmitWorkAnswerApi(answerJSON)
}

// cqieSubmitStudyProvider calls the position-window SubmitStudyTimeApi (updateStudyVideoPlan).
// id is the studyId returned by GetVideoStudyIdApi. Replaceable for tests.
var cqieSubmitStudyProvider = func(cache *cqieApi.CqieUserCache, id, version, courseId, studentCourseId, unitId, videoId, coursewareId string, startPos, stopPos, maxPos int) (string, error) {
	return cache.SubmitStudyTimeApi(id, version, courseId, studentCourseId, unitId, videoId, time.Now(), coursewareId, startPos, stopPos, maxPos, 3, nil)
}

// cqieCache constructs an api cache from a session.
func cqieCache(sess SessionData) *cqieApi.CqieUserCache {
	cache := &cqieApi.CqieUserCache{Account: sess.Account}
	cache.SetAccess_Token(sess.Token)
	cache.SetCookie(sess.Cookies)
	cache.SetStudentId(strOf(sess.Extra["studentId"]))
	cache.SetUserId(strOf(sess.Extra["userId"]))
	cache.SetOrgId(strOf(sess.Extra["orgId"]))
	cache.SetDeptId(strOf(sess.Extra["deptId"]))
	cache.SetOrgMajorId(strOf(sess.Extra["orgMajorId"]))
	cache.SetUserName(strOf(sess.Extra["userName"]))
	cache.SetMobile(strOf(sess.Extra["mobile"]))
	return cache
}

// --- login state machine ---

func startLoginCqie(input AccountInput) (StartLoginResult, error) {
	imgB64, cookie, uuidStr, err := cqieCaptchaProvider(input.Account)
	if err != nil {
		return StartLoginResult{}, fmt.Errorf("captcha fetch failed: %w", err)
	}
	task := pendingLogins.create("cqie", input.Account)
	pendingLogins.setExtra(task.TaskID, map[string]interface{}{
		"password": input.Password,
		"cookie":   cookie,
		"uuid":     uuidStr,
	})
	ch := &OcrChallenge{
		TaskID:      task.TaskID,
		Platform:    "cqie",
		Type:        ChallengeTypeImageOCR,
		ImageBase64: imgB64,
		Hint:        "图形验证码 — 请输入图中字符",
	}
	return StartLoginResult{Status: LoginStatusChallenge, TaskID: task.TaskID, Challenge: ch}, nil
}

func continueLoginCqie(task *PendingLoginTask, ocrText string) (ContinueLoginResult, error) {
	password, _ := task.Extra["password"].(string)
	cookie, _ := task.Extra["cookie"].(string)
	uuidStr, _ := task.Extra["uuid"].(string)

	cache := &cqieApi.CqieUserCache{Account: task.Account}
	cache.Password = password // must be set before LoginApi encrypts it
	cache.SetCookie(cookie)
	cache.SetUUID(uuidStr)
	cache.SetVerCode(ocrText)

	loginRaw, err := cqieLoginProvider(cache)
	pendingLogins.delete(task.TaskID)
	if err != nil {
		return ContinueLoginResult{}, err
	}

	var loginResp struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			AccessToken string `json:"access_token"`
			User        struct {
				Token  string `json:"token"`
				AppId  string `json:"appId"`
				DeptId string `json:"deptId"`
			} `json:"user"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(loginRaw), &loginResp); err != nil {
		return ContinueLoginResult{}, fmt.Errorf("login parse error: %w", err)
	}
	if loginResp.Msg == "验证码有误！" {
		return ContinueLoginResult{}, fmt.Errorf("cqie: 验证码有误，请重新获取")
	}
	if loginResp.Code != 200 || loginResp.Data.AccessToken == "" {
		return ContinueLoginResult{}, fmt.Errorf("cqie: login failed: %s", loginRaw)
	}
	cache.SetAccess_Token(loginResp.Data.AccessToken)

	userRaw, err := cqieUserDetailsProvider(cache)
	if err != nil {
		return ContinueLoginResult{}, err
	}
	var userResp struct {
		Data struct {
			UserId     string `json:"userId"`
			DeptId     string `json:"deptId"`
			Id         string `json:"id"`
			UserName   string `json:"userName"`
			OrgId      string `json:"orgId"`
			Mobile     string `json:"mobile"`
			OrgMajorId string `json:"orgMajorId"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(userRaw), &userResp); err != nil {
		return ContinueLoginResult{}, fmt.Errorf("user details parse error: %w", err)
	}
	sess := SessionData{
		Platform: "cqie",
		Account:  task.Account,
		Token:    loginResp.Data.AccessToken,
		Cookies:  cookie,
		Extra: map[string]interface{}{
			"studentId":  userResp.Data.Id,
			"userId":     userResp.Data.UserId,
			"orgId":      userResp.Data.OrgId,
			"deptId":     userResp.Data.DeptId,
			"orgMajorId": userResp.Data.OrgMajorId,
			"userName":   userResp.Data.UserName,
			"mobile":     userResp.Data.Mobile,
		},
	}
	return ContinueLoginResult{Status: LoginStatusDone, Session: &sess}, nil
}

// --- GetCourses ---

func getCoursesCqie(sess SessionData) (CourseListResult, error) {
	cache := cqieCache(sess)
	raw, err := cqieCoursesProvider(cache)
	if err != nil {
		return CourseListResult{}, err
	}
	var resp struct {
		Msg  string `json:"msg"`
		Data struct {
			Records []struct {
				Id              string  `json:"id"`
				Name            string  `json:"name"`
				StudentCourseId string  `json:"studentCourseId"`
				CoursewareId    string  `json:"coursewareId"`
				Version         string  `json:"version"`
				Learned         string  `json:"learned"`
				SumTime         *string `json:"sumTime"`
				HaveTime        *string `json:"haveTime"`
				SumUnit         float64 `json:"sumUnit"`
				HaveUnit        float64 `json:"haveUnit"`
			} `json:"records"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseListResult{}, fmt.Errorf("cqie: courses parse error: %w", err)
	}
	if resp.Msg != "操作成功" {
		return CourseListResult{}, fmt.Errorf("cqie: courses request failed: %s", raw)
	}
	items := make([]CourseItem, 0, len(resp.Data.Records))
	for _, r := range resp.Data.Records {
		if r.SumTime == nil || r.HaveTime == nil {
			continue
		}
		items = append(items, CourseItem{
			ID:   r.Id,
			Name: r.Name,
			Raw: map[string]interface{}{
				"courseId":        r.Id,
				"studentCourseId": r.StudentCourseId,
				"coursewareId":    r.CoursewareId,
				"version":         r.Version,
				"learned":         r.Learned,
			},
		})
	}
	return CourseListResult{Platform: "cqie", Courses: items}, nil
}

// --- GetCourseDetail (field-forwarding wrapper) ---

func getCourseDetailCqie(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("cqie: courseJSON.id (or raw.courseId) is required")
	}
	raw := map[string]interface{}{
		"courseId":        courseId,
		"studentCourseId": strOf(input.Raw["studentCourseId"]),
		"coursewareId":    strOf(input.Raw["coursewareId"]),
		"version":         strOf(input.Raw["version"]),
	}
	for k, v := range input.Raw {
		if _, exists := raw[k]; !exists {
			raw[k] = v
		}
	}
	item := CourseItem{ID: courseId, Name: strOf(input.Raw["name"]), Raw: raw}
	return CourseDetailResult{Platform: "cqie", ParentID: courseId, Items: []CourseItem{item}}, nil
}

// --- GetTasks ---

func getTasksCqie(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return TaskListResult{}, fmt.Errorf("cqie: courseJSON.id (or raw.courseId) is required")
	}
	studentCourseId := strOf(input.Raw["studentCourseId"])
	if studentCourseId == "" {
		return TaskListResult{}, fmt.Errorf("cqie: courseJSON.raw.studentCourseId is required")
	}
	version := strOf(input.Raw["version"])
	coursewareId := strOf(input.Raw["coursewareId"])

	cache := cqieCache(sess)
	raw, err := cqieDetailProvider(cache, courseId, studentCourseId, version)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("cqie: tasks request failed: %w", err)
	}
	if !strings.Contains(raw, "操作成功") {
		return TaskListResult{}, fmt.Errorf("cqie: tasks request failed: %s", raw)
	}
	tasks := cqieExtractVideos(raw, courseId, studentCourseId, coursewareId, version)
	return TaskListResult{Platform: "cqie", ParentID: courseId, Tasks: tasks}, nil
}

// cqieExtractVideos traverses the PullProgressDetailApi nested JSON and returns a flat video list.
func cqieExtractVideos(raw, courseId, studentCourseId, coursewareId, version string) []TaskItem {
	var resp struct {
		Data []map[string]interface{} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil
	}
	tasks := make([]TaskItem, 0)
	for _, chapter := range resp.Data {
		// videos directly on chapter
		tasks = append(tasks, cqieVideosFromNode(chapter, courseId, studentCourseId, coursewareId, version)...)
		// videos in children
		if children, ok := chapter["children"].([]interface{}); ok {
			for _, child := range children {
				if node, ok := child.(map[string]interface{}); ok {
					tasks = append(tasks, cqieVideosFromNode(node, courseId, studentCourseId, coursewareId, version)...)
				}
			}
		}
	}
	return tasks
}

func cqieVideosFromNode(node map[string]interface{}, courseId, studentCourseId, coursewareId, version string) []TaskItem {
	vList, ok := node["courseCatalogVideoVos"].([]interface{})
	if !ok {
		return nil
	}
	tasks := make([]TaskItem, 0, len(vList))
	for _, v := range vList {
		obj, ok := v.(map[string]interface{})
		if !ok {
			continue
		}
		vid, _ := obj["id"].(string)
		name, _ := obj["name"].(string)
		unitId, _ := obj["unitId"].(string)
		timeLength := 0
		if t, ok := obj["timeLength"].(float64); ok {
			timeLength = int(t)
		}
		studyTime := cqieClockSeconds(strOf(obj["haveTime"]))
		progress := 0.0
		if timeLength > 0 {
			progress = float64(studyTime) * 100 / float64(timeLength)
			if progress > 100 {
				progress = 100
			}
		}
		status := "not_started"
		if timeLength > 0 && studyTime >= timeLength {
			status = "completed"
		} else if studyTime > 0 {
			status = "in_progress"
		}
		segments := cqieSegmentsFromVideo(obj, courseId, unitId)
		tasks = append(tasks, TaskItem{
			ID:       vid,
			Name:     name,
			Type:     "video",
			Progress: progress,
			Status:   status,
			Raw: map[string]interface{}{
				"videoId":         vid,
				"courseId":        courseId,
				"unitId":          unitId,
				"studentCourseId": studentCourseId,
				"coursewareId":    coursewareId,
				"version":         version,
				"timeLength":      timeLength,
				"studyTime":       studyTime,
				"maxCurrentPos":   studyTime,
				"segments":        segments,
			},
		})
	}
	return tasks
}

func cqieClockSeconds(value string) int {
	var hour, minute, second int
	if _, err := fmt.Sscanf(value, "%d:%d:%d", &hour, &minute, &second); err != nil {
		return 0
	}
	return hour*3600 + minute*60 + second
}

func cqieSegmentsFromVideo(video map[string]interface{}, fallbackCourseID, fallbackUnitID string) []map[string]interface{} {
	segments := make([]map[string]interface{}, 0)
	segmentGroups, _ := video["courseCatalogVideoSegments"].([]interface{})
	for _, rawGroup := range segmentGroups {
		group, ok := rawGroup.(map[string]interface{})
		if !ok {
			continue
		}
		courseID := strOf(group["courseId"])
		if courseID == "" {
			courseID = fallbackCourseID
		}
		unitID := strOf(group["unitId"])
		if unitID == "" {
			unitID = fallbackUnitID
		}
		ranges, _ := group["videoSegmentKnowledgeTimeRangesVos"].([]interface{})
		for _, rawRange := range ranges {
			rangeObj, ok := rawRange.(map[string]interface{})
			if !ok {
				continue
			}
			segments = append(segments, map[string]interface{}{
				"id":              strOf(rangeObj["id"]),
				"segmentId":       strOf(rangeObj["segmentId"]),
				"knowledgeNodeId": strOf(rangeObj["knowledgeNodeId"]),
				"courseId":        courseID,
				"unitId":          unitID,
				"segmentName":     strOf(group["segmentName"]),
				"startTimeStr":    strOf(rangeObj["startTimeStr"]),
				"endTimeStr":      strOf(rangeObj["endTimeStr"]),
			})
		}
	}
	return segments
}

func cqieSaveVideoPosition(
	cache *cqieApi.CqieUserCache,
	courseId, studentCourseId, unitId, videoId, coursewareId, version string,
	segments interface{},
	startPos, stopPos int,
) (string, error) {
	baseRaw, err := cqieSaveStudyProvider(cache, courseId, studentCourseId, unitId, videoId, coursewareId, version, startPos, stopPos)
	if err != nil {
		return "", err
	}
	var baseResp struct {
		Msg  string `json:"msg"`
		Data struct {
			Id string `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(baseRaw), &baseResp); err != nil {
		return "", fmt.Errorf("parse save response: %w", err)
	}
	if baseResp.Msg != "操作成功" {
		return "", fmt.Errorf("save study position failed: %s", baseRaw)
	}

	segmentList, _ := segments.([]interface{})
	for _, rawSegment := range segmentList {
		segment, ok := rawSegment.(map[string]interface{})
		if !ok {
			continue
		}
		segmentID := strOf(segment["id"])
		if segmentID == "" {
			continue
		}
		segmentCourseID := strOf(segment["courseId"])
		if segmentCourseID == "" {
			segmentCourseID = courseId
		}
		segmentUnitID := strOf(segment["unitId"])
		if segmentUnitID == "" {
			segmentUnitID = unitId
		}
		segmentRaw, err := cqieSaveSegmentProvider(
			cache, segmentCourseID, studentCourseId, segmentUnitID, videoId, coursewareId,
			segmentID, strconv.Itoa(stopPos), version, startPos, stopPos,
		)
		if err != nil {
			return "", err
		}
		if !strings.Contains(segmentRaw, "操作成功") {
			return "", fmt.Errorf("save segment position failed: %s", segmentRaw)
		}
		knowledgeNodeID := strOf(segment["knowledgeNodeId"])
		if knowledgeNodeID != "" {
			if err := cqieAnswerEmbeddedWork(cache, segmentID, knowledgeNodeID, studentCourseId, segmentCourseID, segmentUnitID, videoId, version); err != nil {
				return "", err
			}
		}
	}
	return baseResp.Data.Id, nil
}

func cqieAnswerEmbeddedWork(
	cache *cqieApi.CqieUserCache,
	segmentID, knowledgeNodeID, studentCourseId, courseId, unitId, videoId, version string,
) error {
	paperRaw, err := cqiePullVideoWorkProvider(cache, segmentID, studentCourseId, unitId)
	if err != nil {
		return err
	}
	var paper struct {
		Code int `json:"code"`
		Data []struct {
			Id              string `json:"id"`
			QuestionType    int    `json:"questionType"`
			ReferenceAnswer string `json:"referenceAnswer"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(paperRaw), &paper); err != nil {
		return fmt.Errorf("parse embedded work: %w", err)
	}
	if paper.Code != 0 && paper.Code != 200 {
		return fmt.Errorf("pull embedded work failed: %s", paperRaw)
	}
	if len(paper.Data) == 0 {
		return nil
	}
	type answerItem struct {
		SubmitAnswer       string `json:"submitAnswer"`
		ExercisesId        string `json:"exercisesId"`
		QuestionType       int    `json:"questionType"`
		ReferenceAnswer    string `json:"referenceAnswer"`
		RecordId           string `json:"recordId"`
		SegmentKnowledgeId string `json:"segmentKnowledgeId"`
		UpdateCount        int    `json:"updateCount"`
	}
	type answerPayload struct {
		PoList             []answerItem `json:"poList"`
		StudentCourseId    string       `json:"studentCourseId"`
		UnitId             string       `json:"unitId"`
		CourseId           string       `json:"courseId"`
		SegmentKnowledgeId string       `json:"segmentKnowledgeId"`
		StudentId          string       `json:"studentId"`
		VideoId            string       `json:"videoId"`
		DeptId             string       `json:"deptId"`
		MajorId            string       `json:"majorId"`
		Version            string       `json:"version"`
		OrgId              string       `json:"orgId"`
	}
	payload := answerPayload{
		StudentCourseId: studentCourseId, UnitId: unitId, CourseId: courseId,
		SegmentKnowledgeId: segmentID, StudentId: cache.GetStudentId(), VideoId: videoId,
		DeptId: cache.GetDeptId(), MajorId: cache.GetOrgMajorId(), Version: version, OrgId: cache.GetOrgId(),
	}
	for _, question := range paper.Data {
		payload.PoList = append(payload.PoList, answerItem{
			SubmitAnswer: question.ReferenceAnswer, ExercisesId: question.Id,
			QuestionType: question.QuestionType, ReferenceAnswer: question.ReferenceAnswer,
			SegmentKnowledgeId: knowledgeNodeID,
		})
	}
	answerJSON, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	answerRaw, err := cqieSubmitWorkAnswerProvider(cache, string(answerJSON))
	if err != nil {
		return err
	}
	var answerResp struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal([]byte(answerRaw), &answerResp); err != nil {
		return fmt.Errorf("parse embedded work submit: %w", err)
	}
	if answerResp.Code != 0 && answerResp.Code != 200 {
		return fmt.Errorf("submit embedded work failed: %s", answerRaw)
	}
	return nil
}

// --- RunTask ---

// runTaskCqie drives a cqie video study step.
//
// The Android host owns the loop/mode; mobile-core exposes single-step primitives
// selected by options.action:
//   - "start": GetVideoStudyIdApi only; returns raw.studyId / coursewareId / maxCurrentPos.
//   - "submit"/"continue"/"end": one position-window SubmitStudyTimeApi using a
//     host-supplied raw.studyId and options.startPos/stopPos/maxPos.
//   - "" (legacy): GetVideoStudyId + SaveStudyTime(stopPos=timeLength) one-shot,
//     also surfacing studyId/coursewareId/maxCurrentPos in Raw.
//
// The host loop is: start -> for{ submit(startPos,stopPos,maxPos); pos+=3; Sleep(3s) }
// until maxPos>=timeLength (普通), or a single full-window submit (暴力).
func runTaskCqie(sess SessionData, input TaskInput) (RunTaskResult, error) {
	videoId := input.ID
	if videoId == "" {
		videoId = strOf(input.Raw["videoId"])
	}
	if videoId == "" {
		return RunTaskResult{}, fmt.Errorf("cqie: taskJSON.id (or raw.videoId) is required")
	}
	courseId := strOf(input.Raw["courseId"])
	if courseId == "" {
		return RunTaskResult{}, fmt.Errorf("cqie: taskJSON.raw.courseId is required")
	}
	studentCourseId := strOf(input.Raw["studentCourseId"])
	if studentCourseId == "" {
		return RunTaskResult{}, fmt.Errorf("cqie: taskJSON.raw.studentCourseId is required")
	}
	unitId := strOf(input.Raw["unitId"])
	coursewareId := strOf(input.Raw["coursewareId"])
	version := strOf(input.Raw["version"])
	timeLength := optInt(input.Raw["timeLength"], 0)
	action := strOf(input.Options["action"])

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "dry_run",
			Message: fmt.Sprintf("courseId=%s studentCourseId=%s unitId=%s", courseId, studentCourseId, unitId)}, nil
	}

	cache := cqieCache(sess)

	if action == "getProgress" {
		progressRaw, err := cqieDetailProvider(cache, courseId, studentCourseId, version)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("cqie: get progress failed: %w", err)
		}
		if !strings.Contains(progressRaw, "操作成功") {
			return RunTaskResult{}, fmt.Errorf("cqie: get progress failed: %s", progressRaw)
		}
		for _, task := range cqieExtractVideos(progressRaw, courseId, studentCourseId, coursewareId, version) {
			if task.ID != videoId {
				continue
			}
			status := "incomplete"
			if task.Progress >= 100 || task.Status == "completed" {
				status = "done"
			}
			return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: status,
				Message: fmt.Sprintf("server progress=%.2f", task.Progress),
				Raw: map[string]interface{}{
					"progress": task.Progress, "studyTime": task.Raw["studyTime"],
					"timeLength": task.Raw["timeLength"],
				}}, nil
		}
		return RunTaskResult{}, fmt.Errorf("cqie: video %s missing from progress response", videoId)
	}

	// submit-only path: host already holds a studyId from a prior "start".
	if action == "submit" || action == "continue" {
		studyID := strOf(input.Raw["studyId"])
		if studyID == "" {
			return RunTaskResult{}, fmt.Errorf("cqie: action=%s requires raw.studyId from a prior action=start", action)
		}
		startPos := optInt(input.Options["startPos"], 0)
		stopPos := optInt(input.Options["stopPos"], timeLength)
		maxPos := optInt(input.Options["maxPos"], stopPos)
		submitRaw, err := cqieSubmitStudyProvider(cache, studyID, version, courseId, studentCourseId, unitId, videoId, coursewareId, startPos, stopPos, maxPos)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("cqie: submit study time failed: %w", err)
		}
		if !strings.Contains(submitRaw, "操作成功") {
			return RunTaskResult{}, fmt.Errorf("cqie: submit study time failed: %s", submitRaw)
		}
		return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "submitted",
			Message: fmt.Sprintf("startPos=%d stopPos=%d maxPos=%d", startPos, stopPos, maxPos),
			Raw:     map[string]interface{}{"studyId": studyID, "startPos": startPos, "stopPos": stopPos, "maxPos": maxPos}}, nil
	}

	// Console finalises both normal and fast modes with saveStudyVideoPlan.
	if action == "end" {
		startPos := optInt(input.Options["startPos"], timeLength)
		stopPos := optInt(input.Options["stopPos"], startPos)
		if _, err := cqieSaveVideoPosition(
			cache, courseId, studentCourseId, unitId, videoId, coursewareId, version,
			input.Raw["segments"], startPos, stopPos,
		); err != nil {
			return RunTaskResult{}, fmt.Errorf("cqie: final save failed: %w", err)
		}
		return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "done",
			Message: fmt.Sprintf("saved startPos=%d stopPos=%d", startPos, stopPos),
			Raw: map[string]interface{}{
				"startPos": startPos, "stopPos": stopPos,
				"maxPos": optInt(input.Options["maxPos"], stopPos),
			}}, nil
	}

	// Console starts a video by saving the current position. data.id is the study id
	// required by updateStudyVideoPlan.
	if action == "start" {
		studyTime := optInt(input.Raw["studyTime"], 0)
		studyID, err := cqieSaveVideoPosition(
			cache, courseId, studentCourseId, unitId, videoId, coursewareId, version,
			input.Raw["segments"], studyTime, studyTime,
		)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("cqie: start save failed: %w", err)
		}
		if studyID == "" {
			return RunTaskResult{}, fmt.Errorf("cqie: start save failed: response missing data.id")
		}
		return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "started",
			Message: fmt.Sprintf("studyId=%s maxCurrentPos=%d", studyID, studyTime),
			Raw: map[string]interface{}{
				"studyId": studyID, "coursewareId": coursewareId,
				"maxCurrentPos": studyTime, "courseId": courseId,
				"studentCourseId": studentCourseId, "unitId": unitId,
				"videoId": videoId, "version": version, "timeLength": timeLength,
				"segments": input.Raw["segments"],
			}}, nil
	}

	// Step 1: GetVideoStudyIdApi registers the study session and yields studyId/coursewareId/maxCurrentPos.
	startRaw, err := cqieStartVideoProvider(cache, studentCourseId, videoId, version)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("cqie: start study failed: %w", err)
	}
	var startResp struct {
		Msg  string `json:"msg"`
		Data struct {
			Id            string  `json:"id"`
			CoursewareId  string  `json:"coursewareId"`
			MaxCurrentPos float64 `json:"maxCurrentPos"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(startRaw), &startResp); err != nil {
		return RunTaskResult{}, fmt.Errorf("cqie: start study failed: parse error")
	}
	if startResp.Msg != "操作成功" {
		return RunTaskResult{}, fmt.Errorf("cqie: start study failed: %s", startRaw)
	}
	if startResp.Data.CoursewareId != "" {
		coursewareId = startResp.Data.CoursewareId
	}

	startMeta := map[string]interface{}{
		"studyId":         startResp.Data.Id,
		"coursewareId":    coursewareId,
		"maxCurrentPos":   startResp.Data.MaxCurrentPos,
		"courseId":        courseId,
		"studentCourseId": studentCourseId,
		"unitId":          unitId,
		"videoId":         videoId,
		"version":         version,
		"timeLength":      timeLength,
	}

	if action == "start" {
		return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "started",
			Message: fmt.Sprintf("studyId=%s maxCurrentPos=%v", startResp.Data.Id, startResp.Data.MaxCurrentPos),
			Raw:     startMeta}, nil
	}

	// legacy one-shot: SaveStudyTimeApi records full duration.
	stopPos := timeLength
	saveRaw, err := cqieSaveStudyProvider(cache, courseId, studentCourseId, unitId, videoId, coursewareId, version, 0, stopPos)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("cqie: save study time failed: %w", err)
	}
	if !strings.Contains(saveRaw, "操作成功") {
		return RunTaskResult{}, fmt.Errorf("cqie: save study time failed: %s", saveRaw)
	}
	return RunTaskResult{Platform: "cqie", TaskID: videoId, Status: "submitted",
		Message: fmt.Sprintf("stopPos=%d", stopPos),
		Raw:     startMeta}, nil
}
