# BOT 소유 SponsorBlock과 YouTube.js 챕터 전환 계획

상태: lavalink_mixer의 `startPositionMs` 구현과 로컬 테스트를 완료했다. BOT과 data-api 변경 및 배포는 아직 없다.

## 결정

- SponsorBlock 구간은 data-api의 작은 HTTP 클라이언트가 [공식 `GET /api/skipSegments`](https://wiki.sponsor.ajay.app/w/API_Docs)에서 가져온다. 캐시는 data-api가 소유한다. BOT이 길드별 활성 카테고리, 현재 곡 스킵, 다음 곡 시작 위치를 결정한다.
- YouTube 챕터는 data-api에서 [YouTube.js](https://github.com/LuanRT/YouTube.js)로 가져온다. 현재 BOT의 `/v1/chapters` 소비자와 응답 형식은 유지한다. SponsorBlock 플러그인의 `ChaptersLoaded` 이벤트에는 의존하지 않는다.
- Lavalink SponsorBlock 플러그인은 BOT 경로가 완성되고 검증된 뒤 제거한다. 동시에 두 스키퍼가 실행되면 중복 seek가 생긴다.
- lavalink_mixer의 `POST /mixer/queue/arm`에 **선택적** `startPositionMs`를 추가한다. 이 값은 들어오는 **다음 곡**의 시작 위치다. 현재 곡의 페이드 시작 시각이 아니다.

## 확인한 현재 구조

| 영역 | 현재 | 변경 대상 |
| --- | --- | --- |
| Lavalink SponsorBlock 플러그인 | 현재 곡 시작 후 세그먼트를 조회하고 자동 seek; 카테고리 REST만 제공 | 배포에서 제거 |
| BOT `sponsorBlockHandler.ts` | `SegmentsLoaded`, `SegmentSkipped`, `ChaptersLoaded` 이벤트 처리 | BOT 자체 스킵 제어와 알림으로 대체 |
| BOT `playerHandler.ts`, `trackStartOrchestrator.ts` | `setSponsorBlock`, 크로스페이드 앞에서 `deleteSponsorBlock`, 다음 곡에서 복원 | 플러그인 제어와 복원 코드 삭제 |
| BOT `mixerHandlers.ts` | `arm`에 곡과 `preserveTransition`만 전달 | 새 위치, 실패 처리, 재시도 재검증 |
| BOT `queueWrapper.ts` | 큐 헤드가 바뀔 때 다음 곡 분석 시작 | SponsorBlock 사전 조회도 시작 |
| data-api `providers/chapters.ts` | 하드코딩된 YouTube 검색 요청, 시청 페이지 HTML 정규식, 임의 JSON 탐색 | YouTube.js `getInfo`와 타입이 있는 챕터 노드로 대체 |
| data-api `/v1/chapters` | 12시간 캐시, `{chapters,_cached}` | 경로와 응답 유지, 공급자만 교체 |

BOT의 `resolveYouTubeVideoId`는 Lavalink가 이미 제공한 `sourceName`과 `identifier`를 읽는 함수이므로 유지한다. 조사한 음악 재생 경로에서 YouTube 내부 JSON/HTML을 직접 파싱하는 부분은 data-api `providers/chapters.ts`에 집중되어 있다. 다른 앱의 URL 판별이나 알림용 링크 생성은 별도 기능이므로 이 계획의 교체 범위가 아니다.

## 1. data-api: SponsorBlock 클라이언트와 캐시

`providers/sponsorblock.ts`를 만든다. 고정 호스트 `https://sponsor.ajay.app`에만 요청하고, `videoID`, 지원하는 스킵 카테고리 전체, `actionType=skip`만 전달한다. 비디오 ID를 호스트나 경로로 쓰지 않는다. 응답은 경계에서 스키마로 검사하고 `category`, `startMs`, `endMs`만 남긴다. 시간은 유한수, `0 <= start < end`여야 하며 비디오 길이를 넘는 잘못된 세그먼트는 BOT이 해당 곡의 길이로 제한한다. `mute`, `poi`, `chapter` 동작은 seek 대상으로 취급하지 않는다.

새 내부 경로:

```http
GET /v1/sponsorblock/segments?videoId=dQw4w9WgXcQ

200 OK
{"segments":[{"category":"intro","startMs":0,"endMs":13520},{"category":"sponsor","startMs":102000,"endMs":131000}],"_cached":true}
```

- data-api는 길드 설정을 받지 않는다. 전체 지원 카테고리의 결과를 비디오 ID와 캐시 스키마 버전으로 공유하고, BOT이 길드 설정에 따라 필터링한다. 카테고리 목록은 현재 BOT 설정 UI와 일치시킨다.
- upstream 404는 **조회 성공, 구간 없음**으로 변환해 `segments: []`를 반환한다. 네트워크 실패·429·5xx·형식 오류는 빈 배열로 바꾸지 않고 오류로 전달한다.
- 캐시 정책 시작값: 양성 24시간, 빈 결과 1시간. 동일 키 동시 요청은 기존 `deduped`로 합치고, 기존 Redis/메모리 `sharedCache`, 회로 차단기, 메트릭 경로를 사용한다. 최신 조회가 실패했을 때만 최대 7일 된 **양성** 결과를 별도 stale 키에서 제공하고, 응답에 `stale: true`를 표시한다. 오래된 빈 결과는 재사용하지 않는다. `serveCached`의 고정 TTL/실패 동작으로 표현되지 않는 부분은 공용 캐시 경로에 작은 확장으로 넣는다.
- 요청 시간, 응답 크기, 세그먼트 개수에 상한을 둔다. 캐시 키는 `dataapi:v2:sponsorblock:<videoId>`처럼 버전을 포함한다. 429 시 즉시 재시도 폭주를 만들지 않는다.
- BOT 쪽에는 `fetchSponsorBlockSegments(videoId)` 클라이언트를 추가한다. 외부 JSON은 data-api 경계에서만 정규화하고 BOT 내부에는 검증된 구간 타입만 전달한다.

## 2. data-api: 챕터 조회를 YouTube.js로 교체

`apps/data-api`에 `youtubei.js`의 검증된 버전을 고정하고, 조회에 사용할 `Innertube` 세션을 프로세스 단위로 재사용한다. YouTube.js의 [`Innertube.getInfo(videoId)`](https://github.com/LuanRT/YouTube.js/blob/main/src/Innertube.ts)는 watch-next 응답까지 읽는다. 소스의 [`VideoInfo.player_overlays`](https://github.com/LuanRT/YouTube.js/blob/main/src/parser/youtube/VideoInfo.ts) → `decorated_player_bar` → `player_bar` → [`markers_map`의 `chapters`](https://github.com/LuanRT/YouTube.js/blob/main/src/parser/classes/MultiMarkersPlayerBar.ts)에 챕터가 타입으로 담긴다. 각 [`Chapter`](https://ytjs.dev/api/youtubei.js/namespaces/YTNodes/classes/Chapter)의 `time_range_start_millis`와 `title`을 사용한다. `getBasicInfo`는 watch-next 경로가 없어 이 목적에 사용하지 않는다.

- 현재의 `parseTimestamp`, `findNodes`, 검색 카드 JSON 탐색, `ytInitialData` 정규식, 직접 만든 YouTube client version을 삭제한다. YouTube.js의 내부 파서 계층을 복사하지 않는다.
- 정렬, 중복 제거, 두 개 미만일 때 빈 결과, 다음 챕터 시작/영상 길이로 `end` 계산은 현재 data-api 계약의 정규화 정책으로 유지한다. 반환형 `{name,start,end,duration}`와 `/v1/chapters` 응답은 그대로 둔다.
- `videoId`와 `durationMs`를 검증한다. 현재 BOT의 **15분 최소 길이 제한은 제거**한다. 플러그인을 제거하면 짧은 영상의 챕터를 공급할 다른 경로가 없기 때문이다. 대신 조회 동시성 상한과 빈 결과의 별도 짧은 TTL을 둔다. `chaptersTrackIdentifier`로 늦은 응답을 버리는 동작은 유지한다.
- `getInfo`에서 챕터가 누락되는 실제 영상이 있으면 YouTube.js가 파싱한 `Video.expandable_metadata`/`MacroMarkersListItem` 경로를 같은 라이브러리 안에서 보조 경로로 사용한다. 원시 YouTube JSON·HTML 탐색 코드는 되살리지 않는다. 챕터 없음과 조회 실패는 분리해 관측한다.
- 기존 양성 결과 12시간 캐시는 유지하되 공급자 교체 시 캐시 키 버전을 올린다. YouTube.js 호출은 한 번에 중복되지 않게 하고 타임아웃과 오류 로그를 유지한다.
- `ChaptersLoaded`가 사라져도 챕터 UI/컨트롤러가 동일한 `{name,start,end,duration}`을 받도록 BOT 연결을 검증한다.

## 3. lavalink_mixer: 다음 곡 시작 위치 프로토콜

```http
POST /mixer/queue/arm
Idempotency-Key: <고유 키>
Content-Type: application/json

{"guildId":"123456789012345678","epoch":42,"encodedTrack":"<position 0의 트랙>","isEmpty":false,"preserveTransition":false,"startPositionMs":13520}
```

- `startPositionMs` 생략은 0ms. `isEmpty: true`에서는 허용하지 않는다. 음수·탐색 불가·알 수 없는 길이·곡 끝에 너무 가까운 위치는 400으로 거부한다. 검증은 멱등성 기록 전에 한다.
- `ProtocolController`의 무장 상태가 위치를 소유한다. 위치를 `arm` 지문에 넣어 같은 키/같은 값은 중복 응답, 같은 키/다른 값은 422로 만든다. invalidate/reset/대체 arm은 이전 위치를 지운다.
- `GET /mixer/info`에 `incomingStartPositionSupported: true`를 넣는다. BOT은 **노드별** 지원 여부를 보고 필드를 사용한다. protocol v2 이벤트 이름과 guild ID 문자열은 유지한다.
- 크로스페이드 보조 플레이어는 재생/PCM 프리필 전에 `max(명시한 위치, 앞부분 무음 끝)`으로 seek한다. 비트 정렬은 이 위치 이후에서만 적용한다. 분석 창이 위치를 덮지 못하면 일반 크로스페이드로 돌아간다.
- gapless 진입에도 같은 시작 위치를 적용한다. `preserveTransition`이 true이면 페이드를 하지 않되 명시한 시작 위치는 유지한다. handoff 위치는 기존처럼 **소비한 보조 소스 샘플 + 시작 위치**로 계산한다. 상태 lock 안에서 player mutation lock을 잡지 않는다.
- `overlapStart`에 실제 `incomingStartMs`를 넣어 관측한다. 현재 `MixerRestHandler`, `ProtocolController`, `GuildMixer`, OpenAPI, README, 클라이언트 예제를 함께 바꾼다.

## 4. BOT: 스킵과 크로스페이드 무장

1. 큐 헤드 변경 시 다음 YouTube 곡의 세그먼트를 사전 조회한다. 반복 모드가 고른 대상도 포함한다. 캐시/진행 중 요청은 비디오 ID별로 재사용한다.
2. 현재 곡 시작 시 해당 곡 세그먼트를 로드하고 길드의 현재 활성 카테고리로 필터링한다. 재생 위치가 선택한 구간 안에 들어오면 구간 끝으로 `player.seek`한다. seek 후에는 새 위치를 다시 평가하되, 같은 구간을 반복 처리하지 않는다. 일시정지, 사용자 seek, 곡 교체, 노드 재연결, 설정 변경에 따라 예약/진행 중 작업을 다시 평가한다. 모든 비동기 응답과 seek 직전에 곡 ID·세대 번호를 확인한다.
3. `handleMixerNeedNext`가 다음 곡의 0ms에 닿은 선택 구간과 이어지는 구간들을 합쳐 `startPositionMs`를 계산한다. 예: `0–5초`, `4.9–13.52초` → `13520`. 중간에 떨어진 구간은 시작 위치로 쓰지 않는다. 정상 arm과 409 재시도에서 현재 곡, 큐 대상, epoch, 노드, 설정을 다시 확인한다. 409 재시도는 새 무장에 새 멱등성 키를 쓴다.
4. 현재 곡의 선택된 구간이 예정된 페이드 창과 겹치면 그 경계는 `preserveTransition: true`로 무장한다. BOT이 현재 곡을 seek하는 동안 믹서가 동시에 보조 오디오를 섞지 않게 한다. 페이드가 이미 시작했다면 BOT은 현재 곡을 seek하지 않고 완료/중단 이벤트 뒤 다시 판단한다. 이 경우 스킵보다 전환 일관성을 우선하며, 이후 누락된 스킵이 있으면 다음 곡에서 처리한다.
5. 다음 곡의 조회가 실패하고 SponsorBlock이 활성화되어 있으면 해당 경계는 `preserveTransition: true`로 무장한다. 미지원 믹서 노드도 같은 방식으로 다룬다. 실패를 `0ms`로 위장하지 않는다. 늦게 도착한 결과로 이미 시작한 전환을 seek하거나 다시 무장하지 않는다.
6. 보조 플레이어에서는 SponsorBlock seek를 수행하지 않는다. BOT이 보낸 시작 위치만 믹서가 적용한다. handoff 뒤 현재 곡의 나머지 구간은 BOT 스키퍼가 처리한다. 트랙별 구간과 챕터 상태는 handoff 및 일반 trackStart 경로에서 같은 곡 정체성으로 이관한다.
7. 기존 스킵 알림은 BOT이 실제 seek가 성공한 뒤 발송한다. 플러그인 `SegmentSkipped` 메시지에는 의존하지 않는다.

## 5. 제거 범위와 배포 순서

1. data-api의 SponsorBlock 경로와 YouTube.js 챕터 공급자를 만들고 단독 검증한다. `/v1/chapters` 소비자는 그대로 동작해야 한다.
2. lavalink_mixer의 선택 필드와 capability를 구현해 새 JAR을 만든다. 모든 노드에 설치하고 `/mixer/info`에서 지원을 확인한다.
3. BOT 스키퍼와 무장 위치 전송을 배포하되 처음에는 기능 스위치를 끈다. 전환할 때 기존 플러그인의 길드별 카테고리를 비우고 새 BOT 스키퍼를 켠다. 이미 재생 중인 곡은 재시작하거나 트랙 교체를 기다려 이전 플러그인 marker가 남은 상태에서 이중 seek하지 않게 한다. 구간 데이터의 출처를 data-api 하나로 바꾼 뒤 `ChaptersLoaded`, `SegmentsLoaded`, `SegmentSkipped` listener와 `setSponsorBlock`/`deleteSponsorBlock`/복원 상태를 삭제한다.
4. 새 BOT 동작을 실제 Lavalink 노드에서 확인한 뒤 서버의 SponsorBlock 플러그인 JAR/설정을 제거하고 재시작한다. 이후 임시 스위치를 정리한다. Lavalink 플러그인 없이도 챕터 UI와 스킵 알림이 살아 있어야 한다.
5. BOT 저장소의 현재 작업 트리는 미커밋 변경이 있으므로 구현 시 별도 작업 트리를 사용하고 그 저장소의 브랜치/PR 규칙을 따른다.

## 검증 기준

- **data-api:** 200 세그먼트, 404 빈 결과, 429/5xx 실패, 이상한 구간, 카테고리, `actionType`, 양성/음성 TTL, 동시 요청 합치기, stale 양성 조회. YouTube.js는 챕터 있음/없음/실패와 기존 반환형을 확인한다.
- **믹서:** 0ms 생략, 400/422, invalidate/reset, gapless와 `preserveTransition`, 비트 정렬 하한, 실제 혼합 PCM에 스폰서 앞부분이 없는지와 handoff 위치를 검사한다. 관련 테스트 후 `./gradlew build`를 실행한다.
- **BOT:** 앞부분 연속 구간, 중간 구간, 끝 구간과 페이드 창, 일시정지/seek/스킵/노드 변경/설정 변경, 늦은 조회, 409 재시도, 미지원 노드, 정상/실패 조회를 검사한다. 린트·타입 검사·테스트를 실행한다.
- **실서버:** SponsorBlock 플러그인을 제거한 노드에서 챕터 표시, 현재 곡 스킵, 다음 곡 앞부분을 건너뛴 크로스페이드, 일반 곡 크로스페이드와 갭리스, 스킵 알림을 재생으로 확인한다. 로컬 오디오 테스트만으로 Discord 음성 연결을 증명하지 않는다.

이 문서는 전체 구현 계획이다. lavalink_mixer 변경은 별도 브랜치에 있으며, SponsorBlock 플러그인과 BOT 코드는 아직 변경하지 않았다.
