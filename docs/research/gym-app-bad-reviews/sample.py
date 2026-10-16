import csv,re,random,sys
exec(open('analyze.py',encoding='utf-8').read().split("G=lambda")[0].split("rows=list")[0])
rows=list(csv.DictReader(open('gym_reviews_v2.csv',encoding='utf-8-sig')))
src=open('analyze.py',encoding='utf-8').read()
T=eval(src[src.index('T={')+2:src.index('}\nG=')+1])
mode=sys.argv[1]; random.seed(1)
def th(x): return int(x['공감수'] or 0)
if mode=='neg':
    pool=[r for r in rows if int(r['평점'])<=2 or (int(r['평점'])==3)]
    themes=['워치/웨어러블','로그인/동기화/데이터유실','결제/구독/가격','버그/튕김/느림','광고','루틴/프로그램 추천(AI)','기록 편의/UX(빠른입력)','타이머/휴식','통계/그래프/진행','운동종목/DB/커스텀','수퍼세트/세트관련','단위(kg/lb)/무게','소셜/공유/랭킹']
else:
    pool=[r for r in rows if int(r['평점'])>=4 and len(r['리뷰내용'])>=60]
    themes=['워치/웨어러블','루틴/프로그램 추천(AI)','기록 편의/UX(빠른입력)','통계/그래프/진행','타이머/휴식','운동종목/DB/커스텀','소셜/공유/랭킹','디자인/UI','수퍼세트/세트관련','식단/칼로리','위젯/잠금화면/라이브']
for t in themes:
    m=[r for r in pool if re.search(T[t],r['리뷰내용'],re.I)]
    m.sort(key=lambda r:-th(r))
    pick=m[:2]+random.sample(m[2:],min(3,max(0,len(m)-2)))
    print(f"\n## {t} ({len(m)})")
    for r in pick: print(f"- [{r['앱']}/{r['플랫폼']}/{r['평점']}★/👍{r['공감수']}] {r['리뷰내용'][:230]}")
