package mobilecore

import (
	"encoding/json"
	"fmt"
	"strconv"

	welearnAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/welearn"
	welearnApi "github.com/yatori-dev/yatori-go-mobile-core/api/welearn"
)

func loginWelearn(input AccountInput) (SessionData, error) {
	cache := &welearnApi.WeLearnUserCache{
		Account:  input.Account,
		Password: input.Password,
	}
	if err := welearnAgg.WeLearnLoginAction(cache); err != nil {
		return SessionData{}, err
	}
	return SessionData{
		Platform: "welearn",
		Account:  input.Account,
		Cookies:  cookieSliceToStr(cache.Cookies),
		Extra:    map[string]interface{}{},
	}, nil
}

// welearnCache builds an api cache from the session cookies (Token fallback).
func welearnCache(sess SessionData) *welearnApi.WeLearnUserCache {
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	return &welearnApi.WeLearnUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
}

// --- replaceable providers (test seams) ---

var welearnCourseListProvider = func(cache *welearnApi.WeLearnUserCache) ([]welearnAgg.WeLearnCourse, error) {
	return welearnAgg.WeLearnPullCourseListAction(cache)
}

var welearnCourseInfoProvider = func(cache *welearnApi.WeLearnUserCache, cid string) (string, string, error) {
	return welearnAgg.WeLearnGetCourseInfoAction(cache, welearnAgg.WeLearnCourse{Cid: cid})
}

var welearnChapterProvider = func(cache *welearnApi.WeLearnUserCache, cid, uid, classId string) ([]welearnAgg.WeLearnChapter, error) {
	return welearnAgg.WeLearnPullCourseChapterAction(cache, welearnAgg.WeLearnCourse{Cid: cid, Uid: uid, ClassId: classId})
}

var welearnPointProvider = func(cache *welearnApi.WeLearnUserCache, cid, uid, classId, unitIdx string) ([]welearnAgg.WeLearnPoint, error) {
	idx, _ := strconv.Atoi(unitIdx)
	return welearnAgg.WeLearnPullChapterPointAction(cache,
		welearnAgg.WeLearnCourse{Cid: cid, Uid: uid, ClassId: classId},
		welearnAgg.WeLearnChapter{UnitIdx: idx})
}

var welearnStartStudyProvider = func(cache *welearnApi.WeLearnUserCache, cid, scoId, uid, crate, classId string, isCompleted bool) (string, error) {
	return cache.StartStudyApi(cid, scoId, uid, crate, classId, isCompleted, 3, nil)
}

var welearnSubmitStudyTimeProvider = func(cache *welearnApi.WeLearnUserCache, uid, cid, classId, scoId string) (string, error) {
	return cache.SubmitStudyTimeApi(uid, cid, classId, scoId, 3, nil)
}

var welearnSubmitPlan1Provider = func(cache *welearnApi.WeLearnUserCache, cid, scoId, uid, crate, classId string) (string, error) {
	return cache.SubmitStudyPlan1Api(cid, scoId, uid, crate, classId, 3, nil)
}

var welearnSubmitPlan2Provider = func(cache *welearnApi.WeLearnUserCache, cid, scoId, uid, crate, classId string, progress int, cstatus string) (string, error) {
	return cache.SubmitStudyPlan2Api(cid, scoId, uid, crate, classId, progress, cstatus, 3, nil)
}

// welearnKeepProvider drives one SCORM keep-session tick (session_time/total_time).
// The host calls this every ~60s with an increasing sessionTime. Replaceable for tests.
var welearnKeepProvider = func(cache *welearnApi.WeLearnUserCache, cid, scoId, uid, classId string, sessionTime, totalTime int) (string, error) {
	return cache.KeepPointSessionPlan1Api(cid, scoId, uid, classId, sessionTime, totalTime, 3, nil)
}

// welearnRet extracts the "ret" field; ok=false when absent/non-numeric.
func welearnRet(raw string) (int, bool) {
	var r struct {
		Ret *float64 `json:"ret"`
	}
	if json.Unmarshal([]byte(raw), &r) != nil || r.Ret == nil {
		return 0, false
	}
	return int(*r.Ret), true
}

func welearnScormBaseline(raw string) (map[string]interface{}, error) {
	var response struct {
		Comment string `json:"comment"`
	}
	if err := json.Unmarshal([]byte(raw), &response); err != nil || response.Comment == "" {
		return nil, fmt.Errorf("welearn: invalid study baseline")
	}
	var comment struct {
		CMI struct {
			SessionTime     float64 `json:"session_time"`
			TotalTime       float64 `json:"total_time"`
			ProgressMeasure string  `json:"progress_measure"`
			Score           struct {
				Scaled string `json:"scaled"`
			} `json:"score"`
		} `json:"cmi"`
	}
	if err := json.Unmarshal([]byte(response.Comment), &comment); err != nil {
		return nil, fmt.Errorf("welearn: invalid study baseline: %w", err)
	}
	progress, _ := strconv.Atoi(comment.CMI.ProgressMeasure)
	return map[string]interface{}{
		"sessionTime":     int(comment.CMI.SessionTime),
		"totalTime":       int(comment.CMI.TotalTime),
		"progressMeasure": progress,
		"scaled":          comment.CMI.Score.Scaled,
	}, nil
}

// numToStr robustly converts an int/float/string raw value to a string.
func numToStr(v interface{}) string {
	switch t := v.(type) {
	case string:
		return t
	case float64:
		return strconv.Itoa(int(t))
	case int:
		return strconv.Itoa(t)
	}
	return ""
}

func getCoursesWelearn(sess SessionData) (CourseListResult, error) {
	cache := welearnCache(sess)
	courses, err := welearnCourseListProvider(cache)
	if err != nil {
		return CourseListResult{}, err
	}
	items := make([]CourseItem, 0, len(courses))
	for _, c := range courses {
		items = append(items, CourseItem{
			ID:       c.Cid,
			Name:     c.Name,
			Cover:    c.Img,
			Progress: float64(c.Per),
			Raw: map[string]interface{}{
				"cid":       c.Cid,
				"scid":      c.Scid,
				"type":      c.Type,
				"uid":       c.Uid,
				"classId":   c.ClassId,
				"taskCount": c.TaskCount,
			},
		})
	}
	return CourseListResult{Platform: "welearn", Courses: items}, nil
}

// getCourseDetailWelearn expands a course into its chapter list.
func getCourseDetailWelearn(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	cid := input.ID
	if cid == "" {
		cid = strOf(input.Raw["cid"])
	}
	if cid == "" {
		return CourseDetailResult{}, fmt.Errorf("welearn: courseJSON.id (or raw.cid) is required")
	}
	cache := welearnCache(sess)
	uid := strOf(input.Raw["uid"])
	classId := strOf(input.Raw["classId"])
	if uid == "" || classId == "" {
		u, c, err := welearnCourseInfoProvider(cache, cid)
		if err != nil {
			return CourseDetailResult{}, fmt.Errorf("welearn: course detail failed: %w", err)
		}
		if uid == "" {
			uid = u
		}
		if classId == "" {
			classId = c
		}
	}
	chapters, err := welearnChapterProvider(cache, cid, uid, classId)
	if err != nil {
		return CourseDetailResult{}, fmt.Errorf("welearn: course detail failed: %w", err)
	}
	items := make([]CourseItem, 0, len(chapters))
	for _, ch := range chapters {
		name := ch.Name
		if name == "" {
			name = ch.Unitname
		}
		items = append(items, CourseItem{
			ID:   ch.Id,
			Name: name,
			Raw: map[string]interface{}{
				"cid":       cid,
				"uid":       uid,
				"classId":   classId,
				"unitIdx":   ch.UnitIdx,
				"chapterId": ch.Id,
				"visible":   ch.Visible,
				"unitName":  ch.Unitname,
				"name":      ch.Name,
			},
		})
	}
	return CourseDetailResult{Platform: "welearn", ParentID: cid, Items: items}, nil
}

// getTasksWelearn expands a chapter into its learning-point list.
func getTasksWelearn(sess SessionData, input CourseInput) (TaskListResult, error) {
	cid := strOf(input.Raw["cid"])
	if cid == "" {
		return TaskListResult{}, fmt.Errorf("welearn: courseJSON.raw.cid is required")
	}
	uid := strOf(input.Raw["uid"])
	if uid == "" {
		return TaskListResult{}, fmt.Errorf("welearn: courseJSON.raw.uid is required")
	}
	classId := strOf(input.Raw["classId"])
	if classId == "" {
		return TaskListResult{}, fmt.Errorf("welearn: courseJSON.raw.classId is required")
	}
	unitIdx := numToStr(input.Raw["unitIdx"])
	if unitIdx == "" {
		return TaskListResult{}, fmt.Errorf("welearn: courseJSON.raw.unitIdx is required")
	}
	cache := welearnCache(sess)
	points, err := welearnPointProvider(cache, cid, uid, classId, unitIdx)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("welearn: tasks request failed: %w", err)
	}
	tasks := make([]TaskItem, 0, len(points))
	for _, p := range points {
		status := "not_started"
		progress := 0.0
		if p.IsComplete == "已完成" {
			status = "done"
			progress = 100
		} else if p.LearnCount > 0 {
			status = "in_progress"
		}
		tasks = append(tasks, TaskItem{
			ID:       p.Id,
			Name:     p.Name,
			Status:   status,
			Progress: progress,
			Raw: map[string]interface{}{
				"cid":        cid,
				"uid":        uid,
				"classId":    classId,
				"scoId":      p.Id,
				"pointId":    p.Id,
				"crate":      p.Crate,
				"isComplete": p.IsComplete,
				"isVisible":  p.IsVisible,
				"unitIdx":    unitIdx,
				"learnCount": p.LearnCount,
				"location":   p.Location,
			},
		})
	}
	return TaskListResult{Platform: "welearn", ParentID: input.ID, Tasks: tasks}, nil
}

// runTaskWelearn drives a welearn study step.
//
// The Android host owns the loop/mode; mobile-core exposes single-step primitives
// selected by options.action:
//   - "start": StartStudyApi(false) to enter the SCO session.
//   - "keep"/"continue": one KeepPointSessionPlan1Api(sessionTime,totalTime) tick.
//   - "finalize"/"end": SubmitStudyPlan2Api(progress,cstatus) to record completion.
//   - "" (legacy): time mode = StartStudy(false)+SubmitStudyTime; complete mode
//     (options.complete=true) = StartStudy(true)+Plan1 with Plan2 fallback.
//
// The host time-mode loop is: start -> for{ Sleep(60s); keep(sessionTime,totalTime);
// sessionTime+=60 } until >= endTime -> finalize(progress=100,"completed").
func runTaskWelearn(sess SessionData, input TaskInput) (RunTaskResult, error) {
	scoId := input.ID
	if scoId == "" {
		scoId = strOf(input.Raw["scoId"])
	}
	if scoId == "" {
		scoId = strOf(input.Raw["pointId"])
	}
	if scoId == "" {
		scoId = strOf(input.Raw["id"])
	}
	if scoId == "" {
		return RunTaskResult{}, fmt.Errorf("welearn: taskJSON.id (or raw.scoId/pointId) is required")
	}
	cid := strOf(input.Raw["cid"])
	if cid == "" {
		return RunTaskResult{}, fmt.Errorf("welearn: taskJSON.raw.cid is required")
	}
	uid := strOf(input.Raw["uid"])
	if uid == "" {
		return RunTaskResult{}, fmt.Errorf("welearn: taskJSON.raw.uid is required")
	}
	classId := strOf(input.Raw["classId"])
	if classId == "" {
		return RunTaskResult{}, fmt.Errorf("welearn: taskJSON.raw.classId is required")
	}
	crate := strOf(input.Options["crate"])
	if crate == "" {
		crate = strOf(input.Raw["crate"])
	}
	if crate == "" {
		crate = "100"
	}
	action := strOf(input.Options["action"])
	complete, _ := input.Options["complete"].(bool)

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		mode := "time"
		if complete {
			mode = "complete"
		}
		if action != "" {
			mode = "action=" + action
		}
		return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "dry_run", Message: "mode=" + mode}, nil
	}

	cache := welearnCache(sess)

	switch action {
	case "start":
		submitRaw, err := welearnSubmitStudyTimeProvider(cache, uid, cid, classId, scoId)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: read study baseline failed: %w", err)
		}
		ret, ok := welearnRet(submitRaw)
		if !ok {
			return RunTaskResult{}, fmt.Errorf("welearn: read study baseline failed")
		}
		if _, err := welearnStartStudyProvider(cache, cid, scoId, uid, crate, classId, false); err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: start study failed: %w", err)
		}
		if ret == -1 {
			submitRaw, err = welearnSubmitStudyTimeProvider(cache, uid, cid, classId, scoId)
			if err != nil {
				return RunTaskResult{}, fmt.Errorf("welearn: read study baseline failed: %w", err)
			}
			if retryRet, retryOK := welearnRet(submitRaw); !retryOK || retryRet != 0 {
				return RunTaskResult{}, fmt.Errorf("welearn: read study baseline failed")
			}
		}
		baseline, err := welearnScormBaseline(submitRaw)
		if err != nil {
			return RunTaskResult{}, err
		}
		return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "started", Message: "action=start", Raw: baseline}, nil
	case "keep", "continue":
		sessionTime := optInt(input.Options["sessionTime"], 60)
		totalTime := optInt(input.Options["totalTime"], sessionTime)
		keepRaw, err := welearnKeepProvider(cache, cid, scoId, uid, classId, sessionTime, totalTime)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: keep session failed: %w", err)
		}
		// keepsco response may omit "ret"; only fail when present and non-zero.
		if ret, ok := welearnRet(keepRaw); ok && ret != 0 {
			return RunTaskResult{}, fmt.Errorf("welearn: keep session failed")
		}
		return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "submitted",
			Message: fmt.Sprintf("action=keep sessionTime=%d", sessionTime),
			Raw:     map[string]interface{}{"sessionTime": sessionTime, "totalTime": totalTime}}, nil
	case "finalize", "end":
		progress := optInt(input.Options["progress"], 100)
		cstatus := strOf(input.Options["cstatus"])
		if cstatus == "" {
			cstatus = "completed"
		}
		plan2Raw, err := welearnSubmitPlan2Provider(cache, cid, scoId, uid, crate, classId, progress, cstatus)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: finalize failed: %w", err)
		}
		if ret, ok := welearnRet(plan2Raw); !ok || ret != 0 {
			return RunTaskResult{}, fmt.Errorf("welearn: finalize failed")
		}
		return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "submitted", Message: "action=finalize"}, nil
	}

	if complete {
		if _, err := welearnStartStudyProvider(cache, cid, scoId, uid, crate, classId, true); err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: start study failed: %w", err)
		}
		plan1Raw, err := welearnSubmitPlan1Provider(cache, cid, scoId, uid, crate, classId)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: submit study plan failed: %w", err)
		}
		if ret, ok := welearnRet(plan1Raw); ok && ret == 0 {
			return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "submitted", Message: "mode=complete plan=1"}, nil
		}
		plan2Raw, err := welearnSubmitPlan2Provider(cache, cid, scoId, uid, crate, classId, 100, "completed")
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("welearn: submit study plan failed: %w", err)
		}
		if ret, ok := welearnRet(plan2Raw); !ok || ret != 0 {
			return RunTaskResult{}, fmt.Errorf("welearn: submit study plan failed")
		}
		return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "submitted", Message: "mode=complete plan=2"}, nil
	}

	// time mode
	if _, err := welearnStartStudyProvider(cache, cid, scoId, uid, crate, classId, false); err != nil {
		return RunTaskResult{}, fmt.Errorf("welearn: start study failed: %w", err)
	}
	submitRaw, err := welearnSubmitStudyTimeProvider(cache, uid, cid, classId, scoId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("welearn: submit study time failed: %w", err)
	}
	if ret, ok := welearnRet(submitRaw); !ok || ret != 0 {
		return RunTaskResult{}, fmt.Errorf("welearn: submit study time failed")
	}
	return RunTaskResult{Platform: "welearn", TaskID: scoId, Status: "submitted", Message: "mode=time"}, nil
}
