import csv
import time
from google_play_scraper import Sort, reviews

APPS = [
    {"name": "플랜핏", "app_id": "com.mih.planfit", "lang": "ko", "country": "kr"},
    {"name": "번핏", "app_id": "com.bunnit.haja.android", "lang": "ko", "country": "kr"},
    {"name": "짐워크", "app_id": "pro.godok.gymwork", "lang": "ko", "country": "kr"},
    {"name": "플릭", "app_id": "com.primero.fleekfrontend", "lang": "ko", "country": "kr"},
    {
        "name": "짐데이",
        "app_id": "kr.co.igrove.gymday",
        "fallback_app_id": "com.anthonyng.workoutapp",
        "lang": "ko",
        "country": "kr",
    },
    {"name": "Hevy", "app_id": "com.hevy", "lang": "en", "country": "us"},
    {"name": "Strong", "app_id": "io.strongapp.strong", "lang": "en", "country": "us"},
    {"name": "Fitbod", "app_id": "com.fitbod.fitbod", "lang": "en", "country": "us"},
    {"name": "JEFIT", "app_id": "je.fit", "lang": "en", "country": "us"},
    {"name": "RepCount", "app_id": "sp.repcount", "lang": "en", "country": "us"},
]

OUTPUT_FILE = "gym_bad_reviews.csv"
FETCH_COUNT = 500
TARGET_SCORES = {1, 2, 3}
MIN_CONTENT_LENGTH = 15


def fetch_app_reviews(app_id, lang, country, count):
    result, _ = reviews(app_id, lang=lang, country=country, sort=Sort.NEWEST, count=count)
    return result


def collect_bad_reviews():
    rows = []
    summary = []
    for info in APPS:
        name, app_id = info["name"], info["app_id"]
        fallback = info.get("fallback_app_id")
        used = app_id
        print(f"[{name}] 최신 리뷰 {FETCH_COUNT}개 수집 중... ({app_id})")
        result = []
        try:
            result = fetch_app_reviews(app_id, info["lang"], info["country"], FETCH_COUNT)
        except Exception as e:
            print(f"  -> [{name}] 조회 실패: {e}")
        if not result and fallback:
            print(f"  -> [{name}] 대체 ID({fallback})로 재시도...")
            used = fallback
            try:
                result = fetch_app_reviews(fallback, info["lang"], info["country"], FETCH_COUNT)
            except Exception as e:
                print(f"  -> [{name}] 대체 조회 실패: {e}")
        matched = 0
        for r in result:
            score = r.get("score")
            content = (r.get("content") or "").strip()
            at = r.get("at")
            if score in TARGET_SCORES and len(content.replace(" ", "")) >= MIN_CONTENT_LENGTH:
                date_str = at.strftime("%Y-%m-%d %H:%M:%S") if at else ""
                rows.append([name, score, date_str, content.replace("\r\n", " ").replace("\n", " ")])
                matched += 1
        print(f"  -> [{name}] ({used}) 전체 {len(result)}건 중 조건 부합 {matched}건")
        summary.append((name, used, len(result), matched))
        time.sleep(0.3)

    with open(OUTPUT_FILE, mode="w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["앱이름", "평점", "작성일", "리뷰내용"])
        w.writerows(rows)

    print("\n=== 요약 ===")
    for name, used, total, m in summary:
        print(f"{name} ({used}): 수집 {total}건 / 필터링 {m}건")
    print(f"총 {len(rows)}건 저장 완료 -> {OUTPUT_FILE}")


if __name__ == "__main__":
    collect_bad_reviews()
