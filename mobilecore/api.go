package mobilecore

import (
	"encoding/json"
	"fmt"
	"runtime"
	"strings"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func Init(baseDir string) (result string) {
	defer panicGuard(&result)
	if baseDir == "" {
		logWarn("", "Init: baseDir is empty")
		return errResp("baseDir must not be empty")
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	state.baseDir = baseDir
	state.initialized = true
	logInfo("", "Init completed: "+baseDir)
	return okResp(map[string]string{"baseDir": baseDir, "version": Version})
}

func SetConfig(configJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var cfg MobileConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		logWarn("", "SetConfig: invalid JSON")
		return errResp(fmt.Sprintf("invalid config JSON: %v", err))
	}
	state.mu.Lock()
	state.config = &cfg
	state.mu.Unlock()
	logInfo("", "Config updated")
	return okResp(nil)
}

// SetXuexitongFontTables injects xuexitong font anti-scrape tables. Hosts should
// pass the contents of glyfHashed.json and cmap.json as strings.
func SetXuexitongFontTables(glyfJSON, cmapJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	if err := xxtmobile.SetXxtFontTablesJSON([]byte(glyfJSON), []byte(cmapJSON)); err != nil {
		return errResp(err.Error())
	}
	return okResp(map[string]interface{}{
		"platform": "xuexitong",
		"status":   "loaded",
	})
}

func GetConfig() (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	defer state.mu.RUnlock()
	if !state.initialized {
		return errResp("not initialized: call Init first")
	}
	if state.config == nil {
		return errResp("config not set: call SetConfig first")
	}
	return okResp(state.config)
}

func GetConfigSchema() (result string) {
	defer panicGuard(&result)
	return okResp(configSchema())
}

func HealthCheck() (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	defer state.mu.RUnlock()
	return okResp(map[string]interface{}{
		"version":     Version,
		"goVersion":   runtime.Version(),
		"goOS":        runtime.GOOS,
		"baseDir":     state.baseDir,
		"initialized": state.initialized,
		"configured":  state.config != nil,
	})
}

// Login authenticates a user for the given platform.
// platform: haiqikeji | ketangx | ttcdw | enaea (phase 2)
// accountJSON: {"account":"...","password":"...","url":"..."}
func Login(platform, accountJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	logInfo(platform, "开始登录")
	var input AccountInput
	if err := json.Unmarshal([]byte(accountJSON), &input); err != nil {
		logWarn(platform, "登录：账号 JSON 格式错误")
		return errResp(fmt.Sprintf("invalid accountJSON: %v", err))
	}
	sess, err := dispatchLogin(platform, input)
	if err != nil {
		logError(platform, "登录失败："+err.Error())
		return errResp(err.Error())
	}
	logInfo(platform, "登录成功")
	return okResp(sess)
}

// GetCourses returns the course list for a given session.
// sessionJSON: SessionData JSON returned by Login.
func GetCourses(sessionJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var sess SessionData
	if err := json.Unmarshal([]byte(sessionJSON), &sess); err != nil {
		logWarn("", "GetCourses: invalid sessionJSON")
		return errResp(fmt.Sprintf("invalid sessionJSON: %v", err))
	}
	logInfo(sess.Platform, "获取课程列表")
	courses, err := dispatchGetCourses(sess)
	if err != nil {
		logError(sess.Platform, "获取课程失败："+err.Error())
		return errResp(err.Error())
	}
	logInfo(sess.Platform, fmt.Sprintf("获取到 %d 门课程", len(courses.Courses)))
	return okResp(courses)
}

// RunTask submits a single-step study task for the given platform.
// taskJSON can echo a TaskItem from GetTasks:
//
//	{"platform":"ttcdw","id":"<videoId>","type":"video","raw":{...},"options":{"progress":100,"finish":true}}
//
// options.dryRun=true validates fields and returns status="dry_run" without network call.
func RunTask(sessionJSON, taskJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var sess SessionData
	if err := json.Unmarshal([]byte(sessionJSON), &sess); err != nil {
		return errResp(fmt.Sprintf("invalid sessionJSON: %v", err))
	}
	var input TaskInput
	if err := json.Unmarshal([]byte(taskJSON), &input); err != nil {
		return errResp(fmt.Sprintf("invalid taskJSON: %v", err))
	}
	if input.Platform == "" {
		input.Platform = sess.Platform
	}
	logInfo(input.Platform, "提交任务 id="+input.ID)
	res, err := dispatchRunTask(sess, input)
	if err != nil {
		logError(input.Platform, "任务失败："+err.Error())
		return errResp(err.Error())
	}
	logInfo(input.Platform, "任务完成 "+res.Status+" id="+input.ID)
	return okResp(res)
}

// GetTasks returns the task/node list for a given course entry.
// courseJSON: {"platform":"haiqikeji","id":"<courseId>","raw":{...}}
// Other platforms return "not implemented".
func GetTasks(sessionJSON, courseJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var sess SessionData
	if err := json.Unmarshal([]byte(sessionJSON), &sess); err != nil {
		return errResp(fmt.Sprintf("invalid sessionJSON: %v", err))
	}
	var input CourseInput
	if err := json.Unmarshal([]byte(courseJSON), &input); err != nil {
		return errResp(fmt.Sprintf("invalid courseJSON: %v", err))
	}
	if input.Platform == "" {
		input.Platform = sess.Platform
	}
	logInfo(input.Platform, "获取任务列表 id="+input.ID)
	tasks, err := dispatchGetTasks(sess, input)
	if err != nil {
		logError(input.Platform, "获取任务失败："+err.Error())
		return errResp(err.Error())
	}
	logInfo(input.Platform, fmt.Sprintf("获取到 %d 个任务", len(tasks.Tasks)))
	return okResp(tasks)
}

// GetCourseDetail expands a course entry
// courseJSON should echo back the CourseItem received from GetCourses:
//
//	{"platform":"ttcdw","id":"<projectId>","raw":{"classId":"...","orgId":"..."}}
func GetCourseDetail(sessionJSON, courseJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var sess SessionData
	if err := json.Unmarshal([]byte(sessionJSON), &sess); err != nil {
		return errResp(fmt.Sprintf("invalid sessionJSON: %v", err))
	}
	var input CourseInput
	if err := json.Unmarshal([]byte(courseJSON), &input); err != nil {
		return errResp(fmt.Sprintf("invalid courseJSON: %v", err))
	}
	if input.Platform == "" {
		input.Platform = sess.Platform
	}
	logInfo(input.Platform, "获取课程详情 id="+input.ID)
	detail, err := dispatchGetCourseDetail(sess, input)
	if err != nil {
		logError(input.Platform, "获取课程详情失败："+err.Error())
		return errResp(err.Error())
	}
	logInfo(input.Platform, fmt.Sprintf("课程详情获取完成，共 %d 项", len(detail.Items)))
	return okResp(detail)
}

// StartLogin begins a (potentially multi-step) login flow.
// Challenge platforms (qingshuxuetang, cqie, yinghua) return status="challenge" with
// an imageBase64 captcha; Android OCR feeds the answer into ContinueLogin.
// Sync platforms (haiqikeji, ketangx, ttcdw, enaea, welearn, xuexitong) complete
// immediately and return status="done".
// Skipped/blocked platforms return an error.
// Android should use this instead of Login for a uniform multi-step interface.
func StartLogin(platform, accountJSON string) (result string) {
	defer panicGuard(&result)
	state.mu.RLock()
	initialized := state.initialized
	state.mu.RUnlock()
	if !initialized {
		return errResp("not initialized: call Init first")
	}
	var input AccountInput
	if err := json.Unmarshal([]byte(accountJSON), &input); err != nil {
		logWarn(platform, "登录：账号 JSON 格式错误")
		return errResp(fmt.Sprintf("invalid accountJSON: %v", err))
	}
	logInfo(platform, "开始登录")
	// OCR platforms with implemented state machine
	if platform == "qingshuxuetang" {
		res, err := startLoginQsxt(input)
		if err != nil {
			logError(platform, "获取验证码失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(platform, "等待验证码识别")
		return okResp(res)
	}
	if platform == "cqie" {
		res, err := startLoginCqie(input)
		if err != nil {
			logError(platform, "获取验证码失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(platform, "等待验证码识别")
		return okResp(res)
	}
	if platform == "yinghua" {
		res, err := startLoginYinghua(input)
		if err != nil {
			logError(platform, "获取验证码失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(platform, "等待验证码识别")
		return okResp(res)
	}
	sess, err := dispatchLogin(platform, input)
	if err != nil {
		if strings.Contains(err.Error(), "unsupported platform") {
			return errResp(err.Error())
		}
		logError(platform, "登录失败："+err.Error())
		if isOCRBlocked(platform) {
			return errResp(fmt.Sprintf("platform %q: OCR login state machine not implemented yet — %s", platform, blockedReason(platform)))
		}
		return errResp(fmt.Sprintf("platform %q: %s", platform, blockedReason(platform)))
	}
	logInfo(platform, "登录成功")
	return okResp(StartLoginResult{Status: LoginStatusDone, Session: &sess})
}

// ContinueLogin submits an OCR or captcha result for an in-progress login task.
// taskID is returned by a previous StartLogin or ContinueLogin call.
// resultJSON must be a valid OcrResult JSON string.
func ContinueLogin(taskID, resultJSON string) (result string) {
	defer panicGuard(&result)
	if taskID == "" {
		return errResp("taskId is required")
	}
	task, ok := pendingLogins.get(taskID)
	if !ok {
		return errResp(fmt.Sprintf("taskId %q not found or already expired", taskID))
	}
	var ocrResult OcrResult
	if err := json.Unmarshal([]byte(resultJSON), &ocrResult); err != nil {
		return errResp(fmt.Sprintf("invalid resultJSON: %v", err))
	}
	logInfo(task.Platform, fmt.Sprintf("提交验证码 第%d步 taskId=%s", task.Step, taskID))
	// Validate taskId consistency if provided in result
	if ocrResult.TaskID != "" && ocrResult.TaskID != taskID {
		return errResp(fmt.Sprintf("taskId mismatch: request %q vs result %q", taskID, ocrResult.TaskID))
	}
	switch task.Platform {
	case "qingshuxuetang":
		res, err := continueLoginQsxt(task, ocrResult.Text)
		if err != nil {
			logError(task.Platform, "登录失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(task.Platform, "登录成功")
		return okResp(res)
	case "cqie":
		res, err := continueLoginCqie(task, ocrResult.Text)
		if err != nil {
			logError(task.Platform, "登录失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(task.Platform, "登录成功")
		return okResp(res)
	case "yinghua":
		res, err := continueLoginYinghua(task, ocrResult.Text)
		if err != nil {
			logError(task.Platform, "登录失败："+err.Error())
			return errResp(err.Error())
		}
		logInfo(task.Platform, "登录成功")
		return okResp(res)
	default:
		return errResp(fmt.Sprintf("platform %q: OCR login state machine not implemented yet (step %d)", task.Platform, task.Step))
	}
}

// CancelLogin cancels and removes an in-progress login task.
// Useful when the user cancels or the Android Activity is destroyed.
func CancelLogin(taskID string) (result string) {
	defer panicGuard(&result)
	if taskID == "" {
		return errResp("taskId is required")
	}
	if !pendingLogins.delete(taskID) {
		return errResp(fmt.Sprintf("taskId %q not found or already cancelled", taskID))
	}
	logInfo("", "CancelLogin: cancelled taskId="+taskID)
	return okResp(CancelLoginResult{Status: LoginStatusCancelled, TaskID: taskID})
}
