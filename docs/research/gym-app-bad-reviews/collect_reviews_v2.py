"""경쟁 앱 리뷰 수집 v2.

표본: 2026-10-16 기준 App Store 건강/피트니스 '최고 매출' 차트(kr/us) 상위 운동기록 앱.
iOS는 customerreviews RSS(앱당 최대 500건, 전체 별점), Android는 filter_score_with로 별점별 수집.
4~5점 리뷰는 불만 키워드 포함 여부를 complaint_kw 컬럼에 표시해 이후 직접 분류한다.
"""
import csv
import json
import re
import time
import urllib.request

from google_play_scraper import Sort, reviews

# (이름, App Store id, 스토어 국가, 매출차트 순위, Android 패키지 or None)
IOS_APPS = [
    ("짐워크", 1563624436, "kr", "KR#1", "pro.godok.gymwork"),
    ("플랜핏", 1511876936, "kr", "KR#2", "com.mih.planfit"),
    ("PeakWatch", 6478431075, "kr", "KR#11", None),
    ("Hevy", 1458862350, "kr", "KR#19/US#44", "com.hevy"),
    ("플릭", 1576993198, "kr", "KR#20", "com.primero.fleekfrontend"),
    ("라이즈", 6470998277, "kr", "KR#22", None),
    ("번핏", 1503464984, "kr", "KR#28", "com.bunnit.haja.android"),
    ("과부하", 6762623088, "kr", "KR#34", None),
    ("Boostcamp", 1529354455, "kr", "KR#64", None),
    ("LADDER", 1502936453, "us", "US#2", None),
    ("Fitbod", 1041517543, "us", "US#20", "com.fitbod.fitbod"),
    ("STNDRD", 1573298047, "us", "US#27", None),
    ("Gymverse", 1048454034, "us", "US#42", None),
    ("Strong", 464254577, "us", "US#63", "io.strongapp.strong"),
    ("Lyfta", 6443740936, "us", "US#79/Android1M+", "com.lyfta"),
    ("JEFIT", None, "us", "Android5M+", "je.fit"),
    ("헬스장루틴", None, "kr", "Android10M+", "gymworkout.gym.gymlog.gymtrainer"),
]

OUT = "gym_reviews_v2.csv"
ANDROID_PER_SCORE = 300
MIN_LEN = 1  # 짧은 화풀이 리뷰도 신호라 길이 제한 없음(빈 리뷰만 제외)
KW = re.compile(
    r"아쉽|불편|다만|하지만|그런데|없어서|안\s?되|오류|버그|느려|튕|있으면\s?좋|추가해|개선|"
    r"\bbut\b|however|wish|missing|needs?\b|should|lacks?|annoying|bug|crash|doesn.t|can.t|cannot|"
    r"unfortunately|hope",
    re.I,
)


def clean(t):
    return re.sub(r"\s+", " ", (t or "")).strip()


def ios_reviews(app_id, country, sortby="mostrecent"):
    out = []
    for page in range(1, 11):
        url = f"https://itunes.apple.com/{country}/rss/customerreviews/page={page}/id={app_id}/sortby={sortby}/json"
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                entries = json.load(r)["feed"].get("entry", [])
        except Exception:
            break
        if isinstance(entries, dict):
            entries = [entries]
        got = [e for e in entries if "im:rating" in e]
        if not got:
            break
        for e in got:
            out.append((int(e["im:rating"]["label"]), e["updated"]["label"][:10],
                        clean(e["title"]["label"] + ". " + e["content"]["label"]), ""))
        time.sleep(0.3)
    return out


def android_reviews(pkg, lang, country):
    out = []
    for score in (1, 2, 3, 4, 5):
        for srt in (Sort.NEWEST, Sort.MOST_RELEVANT):
            try:
                res, _ = reviews(pkg, lang=lang, country=country, sort=srt,
                                 count=ANDROID_PER_SCORE, filter_score_with=score)
            except Exception as e:
                print("  android fail", pkg, score, e)
                continue
            for r in res:
                out.append((score, r["at"].strftime("%Y-%m-%d") if r.get("at") else "", clean(r.get("content")), r.get("thumbsUpCount", "")))
            time.sleep(0.3)
    return out


def main():
    rows = []
    for name, aid, cc, rank, pkg in IOS_APPS:
        batches = ([("iOS", ios_reviews(aid, cc) + ios_reviews(aid, cc, "mosthelpful"))] if aid else [])
        if pkg:
            lang = "ko" if cc == "kr" else "en"
            batches.append(("Android", android_reviews(pkg, lang, cc)))
        for platform, rs in batches:
            kept = 0
            seen = set()
            for score, date, text, thumbs in rs:
                if len(text.replace(" ", "")) < MIN_LEN or (score, text) in seen:
                    continue
                seen.add((score, text))
                flag = int(bool(KW.search(text))) if score >= 4 else ""
                rows.append([name, rank, platform, cc, score, date, thumbs, flag, text])
                kept += 1
            print(f"[{name}] {platform}: 수집 {len(rs)} / 보존 {kept}")
    with open(OUT, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["앱", "매출순위", "플랫폼", "국가", "평점", "작성일", "공감수", "complaint_kw(4~5점)", "리뷰내용"])
        w.writerows(rows)
    print(f"총 {len(rows)}건 -> {OUT}")


if __name__ == "__main__":
    main()
