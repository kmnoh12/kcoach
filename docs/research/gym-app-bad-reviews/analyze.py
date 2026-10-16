import csv,re,collections,sys
rows=list(csv.DictReader(open('gym_reviews_v2.csv',encoding='utf-8-sig')))
T={
 '워치/웨어러블':r'워치|와치|watch|wear ?os|apple watch|갤럭시 ?워치|웨어러블|wearable',
 '로그인/동기화/데이터유실':r'로그인|로그아웃|동기화|데이터.{0,6}(날아|사라|삭제|유실|복구)|백업|sync|lost (my )?(data|workout)|backup|log ?in|deleted my',
 '결제/구독/가격':r'구독|결제|환불|유료|가격|비싸|프리미엄|paywall|subscription|subscribe|premium|\bprice|expensive|refund|pay(ing)? ?wall|\bpaid\b|\$\d',
 '버그/튕김/느림':r'버그|오류|튕|렉|느려|멈춤|먹통|안\s?열|bug|crash|freez|laggy|slow|glitch|broken|doesn.t work|not working',
 '광고':r'광고|\bads?\b|advert',
 '루틴/프로그램 추천(AI)':r'추천|루틴 ?(짜|생성|자동)|\bai\b|에이아이|맞춤|recommend|workout plan|generat|personali[sz]|auto.?generat',
 '기록 편의/UX(빠른입력)':r'입력|편하|편리|간편|직관|빠르|쉽게|사용하기 ?쉬|\beasy\b|\bsimple\b|intuitive|\bquick(ly)?\b|convenient|user.friendly|easy to use',
 '타이머/휴식':r'타이머|휴식|쉬는 ?시간|rest timer|\btimer',
 '통계/그래프/진행':r'통계|그래프|차트|분석|progress|\bstats?\b|graph|chart|analytics|1rm|\bprs?\b',
 '운동종목/DB/커스텀':r'종목|운동 ?(추가|목록|라이브러리)|커스텀|직접 ?(추가|만들)|exercise (library|list|database)|custom (exercise|workout)|exercises? library|variety of exercises',
 '수퍼세트/세트관련':r'슈퍼세트|수퍼세트|드롭세트|워밍업|superset|drop ?set|warm.?up|set type',
 '디자인/UI':r'디자인|예쁘|깔끔|\bui\b|\bux\b|\bdesign|beautiful|interface|looks? (good|great|bad)',
 '소셜/공유/랭킹':r'친구|공유|랭킹|커뮤니티|소셜|friend|social|share|community|leaderboard|rank',
 '식단/칼로리':r'식단|칼로리|영양|diet|calor|nutrition|macro|meal',
 '위젯/잠금화면/라이브':r'위젯|잠금화면|다이나믹|live activit|widget|lock ?screen|dynamic island|notification',
 '단위(kg/lb)/무게':r'\bkg\b|\blbs?\b|단위|무게 ?(설정|단위|오류)|\bunits?\b|plate',
 '지원/업데이트':r'업데이트|문의|고객센터|\bupdate|\bsupport\b|developers?\b|customer service',
}
G=lambda r:'neg' if int(r['평점'])<=2 else 'mid' if int(r['평점'])==3 else 'pos'
cnt=collections.defaultdict(collections.Counter); tot=collections.Counter()
for r in rows:
    g=G(r); tot[g]+=1
    for k,p in T.items():
        if re.search(p,r['리뷰내용'],re.I): cnt[g][k]+=1
print('총',len(rows),dict(tot))
print(f"{'주제':28}{'neg%':>7}{'mid%':>7}{'pos%':>7}")
for k in T:
    print(f"{k:28}"+''.join(f"{100*cnt[g][k]/tot[g]:7.1f}" for g in ('neg','mid','pos')))
# per app neg theme top3 (recent-ish: all)
byapp=collections.defaultdict(lambda:collections.defaultdict(collections.Counter)); at=collections.Counter()
for r in rows:
    g=G(r)
    if g!='neg': continue
    at[r['앱']]+=1
    for k,p in T.items():
        if re.search(p,r['리뷰내용'],re.I): byapp[r['앱']]['neg'][k]+=1
print('\n앱별 1~2점 상위 주제')
for a,c in at.most_common():
    top=byapp[a]['neg'].most_common(4)
    print(a,c,'|',', '.join(f"{k} {100*v/c:.0f}%" for k,v in top))
