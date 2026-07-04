package mobile

import (
	"strings"
	"testing"
)

func TestMobileUAIncludesChaoxingSignature(t *testing.T) {
	ua := mobileUA()
	for _, part := range []string{"(schild:", "(device:MI10)", "Language/zh_CN", "ChaoXingStudy_3_6.7.2_android_phone_10941_314", "(@Kalimdor)_"} {
		if !strings.Contains(ua, part) {
			t.Fatalf("mobile UA missing %q: %s", part, ua)
		}
	}
}
