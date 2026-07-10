package mobile

// 学习通滑块验证码

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif"  // register decoders
	_ "image/jpeg" // register decoders
	_ "image/png"  // register decoders
	"io"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"

	"github.com/google/uuid"
)

const sliderMaxAttempts = 4

var errSliderRejected = errors.New("xuexitong slider: verification rejected")

// SliderPass solves a xuexitong slider captcha for the given captchaId and
// referer, returning the validate token to attach to the paper-pull request.
// A fresh challenge is fetched after a coordinate mismatch, matching the
// console behavior without allowing an unbounded retry loop.
func (c *XxtClient) SliderPass(captchaId, referer string) (string, error) {
	var lastErr error
	for attempt := 1; attempt <= sliderMaxAttempts; attempt++ {
		validate, err := c.sliderPassAttempt(captchaId, referer)
		if err == nil {
			return validate, nil
		}
		lastErr = err
		if !errors.Is(err, errSliderRejected) {
			return "", err
		}
	}
	return "", fmt.Errorf("xuexitong slider: verification rejected after %d attempts: %w", sliderMaxAttempts, lastErr)
}

func (c *XxtClient) sliderPassAttempt(captchaId, referer string) (string, error) {
	// 1) conf: extract server time "t"
	conf, err := c.sliderConfApi(captchaId)
	if err != nil {
		return "", err
	}
	serverTime := ""
	if m := regexp.MustCompile(`"t":(\d+)`).FindStringSubmatch(conf); len(m) == 2 {
		serverTime = m[1]
	}
	if serverTime == "" {
		return "", fmt.Errorf("xuexitong slider: server time not found in conf: %s", conf)
	}

	// 2) image json: token + shade/cutout image urls
	imgResp, err := c.sliderImgApi(captchaId, serverTime, referer)
	if err != nil {
		return "", err
	}
	m := regexp.MustCompile(`cx_captcha_function\((\{.*\})\)`).FindStringSubmatch(imgResp)
	if len(m) < 2 {
		return "", fmt.Errorf("xuexitong slider: image json not found")
	}
	var resp struct {
		Token               string `json:"token"`
		ImageVerificationVo struct {
			ShadeImage  string `json:"shadeImage"`
			CutoutImage string `json:"cutoutImage"`
		} `json:"imageVerificationVo"`
	}
	if err := json.Unmarshal([]byte(m[1]), &resp); err != nil {
		return "", fmt.Errorf("xuexitong slider: image json parse error: %w", err)
	}

	// 3) fetch both images, detect offset, submit, read validate
	shade, err := c.pullSliderImg(resp.ImageVerificationVo.ShadeImage)
	if err != nil {
		return "", err
	}
	cutout, err := c.pullSliderImg(resp.ImageVerificationVo.CutoutImage)
	if err != nil {
		return "", err
	}
	x := DetectSlideOffset(shade, cutout)

	passResp, err := c.passSliderApi(captchaId, resp.Token, strconv.Itoa(x), "10", referer)
	if err != nil {
		return "", err
	}
	pm := regexp.MustCompile(`cx_captcha_function\((\{.*\})\)`).FindStringSubmatch(passResp)
	if len(pm) < 2 {
		return "", fmt.Errorf("xuexitong slider: pass json not found")
	}
	var pass struct {
		Result    bool   `json:"result"`
		ExtraData string `json:"extraData"`
	}
	if err := json.Unmarshal([]byte(pm[1]), &pass); err != nil {
		return "", fmt.Errorf("xuexitong slider: pass json parse error: %w", err)
	}
	if !pass.Result {
		return "", fmt.Errorf("%w: %s", errSliderRejected, passResp)
	}
	var extra struct {
		Validate string `json:"validate"`
	}
	if err := json.Unmarshal([]byte(pass.ExtraData), &extra); err != nil {
		return "", fmt.Errorf("xuexitong slider: extraData parse error: %w", err)
	}
	if extra.Validate == "" {
		return "", fmt.Errorf("xuexitong slider: empty validate")
	}
	return extra.Validate, nil
}

func (c *XxtClient) sliderGet(urlStr, referer string) (string, error) {
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept-Language", "zh-CN,en-US;q=0.9")
	req.Header.Add("X-Requested-With", "com.chaoxing.mobile")
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Connection", "keep-alive")
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	addCookies(req, c)
	client := &http.Client{Transport: httpTransport(c)}
	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	return string(body), err
}

func (c *XxtClient) sliderConfApi(captchaId string) (string, error) {
	u := "https://captcha.chaoxing.com/captcha/get/conf?callback=cx_captcha_function&captchaId=" +
		captchaId + "&_=" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	return c.sliderGet(u, "")
}

func (c *XxtClient) sliderImgApi(captchaId, serverTime, referer string) (string, error) {
	captchaKeyHash := md5.Sum([]byte(serverTime + uuid.New().String()))
	captchaKey := hex.EncodeToString(captchaKeyHash[:])
	sum := md5.Sum([]byte(serverTime + captchaId + "slide" + captchaKey))
	md5hex := hex.EncodeToString(sum[:])
	serverTimeInt, _ := strconv.ParseInt(serverTime, 10, 64)
	token := fmt.Sprintf("%s:%d", md5hex, serverTimeInt+300000)
	u := "https://captcha.chaoxing.com/captcha/get/verification/image?callback=cx_captcha_function&captchaId=" +
		captchaId + "&type=slide&version=1.1.20&captchaKey=" + captchaKey + "&token=" + token +
		"&referer=" + url.QueryEscape(referer)
	return c.sliderGet(u, "")
}

func (c *XxtClient) passSliderApi(captchaId, token, xPoint, runEnv, referer string) (string, error) {
	u := "https://captcha.chaoxing.com/captcha/check/verification/result?callback=cx_captcha_function&captchaId=" +
		captchaId + "&type=slide&token=" + token +
		"&textClickArr=" + url.QueryEscape(`[{"x":`+xPoint+`}]`) +
		"&coordinate=" + url.QueryEscape(`[]`) +
		"&runEnv=" + runEnv + "&version=1.1.20&t=a&_=" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	return c.sliderGet(u, referer)
}

func (c *XxtClient) pullSliderImg(imgUrl string) (image.Image, error) {
	req, err := http.NewRequest("GET", imgUrl, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("X-Requested-With", "com.chaoxing.mobile")
	req.Header.Add("Accept", "*/*")
	addCookies(req, c)
	client := &http.Client{Transport: httpTransport(c)}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("xuexitong slider: image http %d", resp.StatusCode)
	}
	img, _, err := image.Decode(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("xuexitong slider: image decode failed: %w", err)
	}
	return img, nil
}

// --- NCC slide-offset detection (pure Go, ported from go-core) ---

// DetectSlideOffset returns the horizontal pixel offset of the cutout (gap) in
// the background image via normalized cross-correlation template matching.
func DetectSlideOffset(bgImg, cutImg image.Image) int {
	bg := toGray(bgImg)
	cut := toGray(cutImg)

	// Chaoxing places the roughly 48x54 puzzle piece on a black 56x160
	// canvas. Matching that entire canvas makes the black padding dominate
	// NCC and frequently selects x=0. Preserve the crop used by the original
	// OpenCV implementation: match the piece interior against a narrow strip
	// of the background at the same vertical position.
	if cutoutY, ok := findCutoutTop(cut); ok {
		template := cropGray(cut, 8, cutoutY+2, 48, cutoutY+44)
		shadeStrip := cropGray(bg, 0, cutoutY-2, len(bg[0]), cutoutY+50)
		if len(template) > 0 && len(template[0]) > 0 && len(shadeStrip) >= len(template) && len(shadeStrip[0]) >= len(template[0]) {
			offsetX, _ := normCrossCorrelation(shadeStrip, template)
			return offsetX - 5
		}
	}

	// Defensive fallback for an unexpected image layout.
	offsetX, _ := normCrossCorrelation(bg, cut)
	return offsetX - 5
}

func findCutoutTop(gray [][]float64) (int, bool) {
	if len(gray) == 0 || len(gray[0]) == 0 {
		return 0, false
	}
	minPixels := len(gray[0]) / 8
	if minPixels < 1 {
		minPixels = 1
	}
	for y, row := range gray {
		visible := 0
		for _, pixel := range row {
			if pixel > 10 {
				visible++
			}
		}
		if visible >= minPixels {
			return y, true
		}
	}
	return 0, false
}

func cropGray(src [][]float64, x0, y0, x1, y1 int) [][]float64 {
	if len(src) == 0 || len(src[0]) == 0 {
		return nil
	}
	if x0 < 0 {
		x0 = 0
	}
	if y0 < 0 {
		y0 = 0
	}
	if x1 > len(src[0]) {
		x1 = len(src[0])
	}
	if y1 > len(src) {
		y1 = len(src)
	}
	if x0 >= x1 || y0 >= y1 {
		return nil
	}
	out := make([][]float64, y1-y0)
	for y := y0; y < y1; y++ {
		out[y-y0] = src[y][x0:x1]
	}
	return out
}

func toGray(img image.Image) [][]float64 {
	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	gray := make([][]float64, h)
	for y := 0; y < h; y++ {
		gray[y] = make([]float64, w)
		for x := 0; x < w; x++ {
			r, g, b, _ := img.At(x+bounds.Min.X, y+bounds.Min.Y).RGBA()
			gray[y][x] = 0.299*float64(r>>8) + 0.587*float64(g>>8) + 0.114*float64(b>>8)
		}
	}
	return gray
}

func normCrossCorrelation(src, tpl [][]float64) (bestX int, bestScore float64) {
	if len(src) == 0 || len(tpl) == 0 || len(src[0]) == 0 || len(tpl[0]) == 0 {
		return 0, -2
	}
	h1, w1 := len(src), len(src[0])
	h2, w2 := len(tpl), len(tpl[0])
	bestScore = -2
	for y := 0; y <= h1-h2; y++ {
		for x := 0; x <= w1-w2; x++ {
			var sumSrc, sumTpl, sumSrc2, sumTpl2, sumMul float64
			num := float64(w2 * h2)
			for j := 0; j < h2; j++ {
				for i := 0; i < w2; i++ {
					a := src[y+j][x+i]
					b := tpl[j][i]
					sumSrc += a
					sumTpl += b
					sumSrc2 += a * a
					sumTpl2 += b * b
					sumMul += a * b
				}
			}
			meanA := sumSrc / num
			meanB := sumTpl / num
			numerator := sumMul - num*meanA*meanB
			denom := math.Sqrt((sumSrc2-num*meanA*meanA)*(sumTpl2-num*meanB*meanB) + 1e-9)
			score := numerator / denom
			if score > bestScore {
				bestScore = score
				bestX = x
			}
		}
	}
	return bestX, bestScore
}
