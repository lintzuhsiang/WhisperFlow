# Android 即時翻譯修正與驗收清單

第一輪以 App 主入口的雙軌影片播放器為範圍。先讓同一段對白可以追蹤到原文、譯文和實際顯示時間，再決定模型、手機與網路各需要多少提前量。以下均為待辦，P0 優先處理，P1 在基準建立後處理，P2 依直播或併發需求排程。標為「量測」的票以實驗報告結案，即使結果顯示現有策略較好也有價值。

## 共用測試素材與量法

- **短片 A**：製作 8 秒日語 MP4，第一秒內開始說話，含兩句對白。另存同音訊的 PCM 和逐句人工標註，至少有句子 ID、起訖毫秒、日文原文與繁中／英文參考譯文。
- **時間軸 B**：製作 90 秒 MP4，每句有唯一可辨認的數字，安排第 60 秒有語音，也保留一段 20 秒靜音。用它測拖曳、舊字幕與慢速翻譯。
- **品質集 C**：收集含乾淨語音、音樂片頭、低音量、日英切換、跨音窗否定、數字和人名的錄音；逐句標來源語言、關鍵意思和時間。先固定測試集與正規化規則，再調參。
- **測試分層**：用可控制完成順序、可回傳固定時間戳與錯誤的假 ASR／MT，加上可推進的時鐘，驗證排程；同素材在真機跑實際引擎，測聲學、效能與 UI。測試報告附上裝置、版本、引擎、實際模型、語言、冷／暖啟動和網路條件。
- **同步指標**：逐句計算「第一次顯示該句時的播放位置 − 人工標註語音起點」，保留正負號，另報絕對誤差 P50／P95。只對有顯示的句子算誤差，另報覆蓋率「有出現對應字幕的參考句數／有聲參考句數」、過期字幕顯示時長、按播放到首幀及首句所花的時間。樣本少時列逐句結果。
- **品質指標**：ASR 用人工原文計算字元錯誤率 CER 或詞錯誤率 WER；MT 分別吃人工原文和 ASR 原文，由同一套標準判定否定、數字、專名與完整性。錯誤、逾時、同語言略過各自計數。

## P0 先讓錯誤可見並修正主路徑

### LT-01 建立可以重播的逐句基準〔測試基礎〕

- **動作**：準備 A、B 和對應標註，加入假 ASR／MT。從同一輪播放匯出引擎和模型識別、媒體代次、音窗 ID、媒體起訖、入列／開始／完成的單調時鐘、首次顯示的播放位置、最終狀態。先保存修改前的結果。
- **驗收**：從一行顯示紀錄能回查同一段音訊和兩個引擎呼叫；從一個沒有顯示的測試句也能查出停在哪個階段。相同腳本重播兩次得到相同句序與媒體時間。報告可區分雲端與本機執行。
- **入口**：`VideoTranslationActivity.initEngines`、`HiddenTrackPlayer`、`onSubtitleEntry`。其他票可先用各自的假資料重現。

### LT-02 修正預設英文目標的重複翻譯〔缺陷〕

- **重現**：雲端 ASR＋MT，A，`auto → en`，開啟原文，用測試端點記錄路徑和 MT 輸入。現況會選語音翻英端點，再把英文交給 MT。
- **驗收**：雙語字幕模式只取原語轉錄；每個成功定稿的日語子句送 MT 一次。假引擎回傳固定日文時，畫面原文及 MT 輸入與該日文一致，譯文為英文。對照 `ja → zh-tw` 和手動 `en → en`，後者走同語言略過。
- **入口**：`OpenAITranscriptionEngine.transcribe`、`HiddenTrackPlayer.processTranslationWindow`。與 LT-03 一起驗短片。

### LT-03 雲端 auto 從首個語音窗開始並處理片尾〔缺陷〕

- **重現**：A 用 auto 播到結尾，再以手選 ja 播一次。雲端沒有獨立的 `detectLanguage`，auto 路徑仍先走 15 秒門檻；對只有 8 秒的 A 查 ASR 呼叫數。另用未達偵測門檻的本機短片測結尾。
- **驗收**：雲端第一個完整語音窗就發送，片尾殘窗也結算；A 的兩個假引擎字幕皆可取得。UI 只在有具體語言證據時顯示已辨識。對本機 auto，片尾未達門檻仍處理已收音訊並回報語言或不確定。
- **入口**：`SpeechToTextEngine.detectLanguage`、`HiddenTrackPlayer.bufferForPriming / tryLockLanguage`、`WhisperAudioProcessor.queueEndOfStream`。

### LT-04 播放中切換語言立即作用〔缺陷〕

- **重現**：B 播放中將目標從英文切繁中，再將來源從 auto 切日文。讓切換前的一個 MT 請求在切換後才回來，檢查其字幕與後續引擎參數。
- **驗收**：保留當前播放位置和播放／暫停狀態；下一個新工作使用新語言，晚到的舊結果不進字幕或近期清單。連續切換兩次，以最後一次選擇為準。
- **入口**：`VideoTranslationActivity.onSourceLangChange / onTargetLangChange`、`HiddenTrackPlayer.generationId`。

### LT-05 依連續完成範圍決定開播和重新緩衝〔缺陷〕

- **重現**：讓假 MT 卡住第一句，PCM 繼續送到後方；另測開頭全靜音。記錄 visible、decoded 和連續完成位置。現況預設達約 2 秒解碼進度或等滿 6 秒就開播；提前量建議上限 20 秒、播放器上限 10 秒。
- **驗收**：第一句 pending 時，後面的完成結果不能跨過缺口宣告就緒；已確認無聲區可推進。若產品選擇逾時照播，測試須看到獨立的逾時狀態與計數。提前量只有一套有效上下限，記錄「要求」和「實際」領先量。測試涵蓋開播、暫停恢復、影片比預讀範圍更短，以及推論失敗時可離開等待。
- **入口**：`DualTrackPlayer.primeRunnable / checkAndCorrectDrift`、`HiddenTrackPlayer.decodedUpToMs / recommendedLeadMs`。依賴 LT-01。

### LT-06 拖曳和重新同步保留落點對白〔缺陷〕

- **重現**：B 拖到 60 秒，再退回 20 秒。第一個拖曳的 MT 故意延遲；另強制走 drift resync。現況清空字幕後隱藏軌跳到「落點＋提前量」。
- **驗收**：兩種跳轉都從落點或其前方開始連續處理，60 秒對白可顯示。快速第二次拖曳後，第一次的工作不能寫回或顯示；暫停中拖曳後仍為暫停。以每代次首個 PCM 區間與字幕紀錄核對。
- **入口**：`DualTrackPlayer.doSeek / checkAndCorrectDrift`、`HiddenTrackPlayer.seekTo`。與 LT-05 共用測試時鐘。

### LT-07 保留 ASR 語句時間戳並準時移除舊字幕〔缺陷〕

- **重現**：輸入媒體 10–15 秒的音窗，假 ASR 回傳窗內相對區間 0.4–1.2 秒和 3.0–4.0 秒。現況以整窗中點 12.5 秒排一筆字幕，舊字幕可在結束後繼續停留。
- **驗收**：兩筆 cue 的起訖為 10.4–11.2 秒與 13–14 秒。純排程測試核對精確時間；無負載的真機假引擎測試記錄實際顯示偏差，以現有 200 ms UI 輪詢間隔另計系統抖動。B 的長靜音區依約定的閱讀寬限清除上一句。有原生 segment 時使用其時間；缺時間戳的引擎須標明估算來源。
- **入口**：雲端 verbose 回應、本機 `whisper_jni.cpp` 取 segment 處、`HiddenTrackPlayer.processTranslationWindow`、`SubtitleStore.getNearestForDisplay`。依賴 LT-01。

### LT-08 將辨識與翻譯失敗從成功字幕中分出〔缺陷〕

- **重現**：以假 HTTP 端點回傳 429、500、逾時；本機注入缺 tokenizer／session。另送一句包含品牌名、翻譯前後文字恰好相同的有效句作對照。
- **驗收**：ASR 能區分無人聲與請求失敗；MT 能區分成功、同語言略過及失敗。失敗時可顯示有標示的原文，譯文成功計數維持零；兩段文字相同的合法翻譯仍算成功。兩個 Android 入口都跑一次。
- **入口**：`SpeechToTextEngine`、`TranslationEngine`、雲端與本機實作、播放器和 `AudioCaptureService` 的消費端。

## P1 語言品質與持續運算

### LT-09 多語模式選到可用的多語模型〔缺陷〕

- **重現**：在測試裝置只提供 bundled `ggml-tiny.en.bin`，以本機影片模式選 ja 或 auto；再加回一份多語模型重試。對照擷取服務的模型選擇。
- **驗收**：模型不足時明確回報缺少多語模型，或選到已驗證的多語替代檔。紀錄實際載入檔名與能力；兩入口均覆蓋手動英文、日文和 auto。
- **入口**：`WhisperCppEngine.loadModel`、`AudioCaptureService.selectAsrModelFilename`。

### LT-10 以有效語音判定語言並支援換語〔量測及策略〕

- **實驗**：C 逐句比對現況與候選策略。先排除無語音片段，再整合多段語言證據；明確分開「偵測中」「已確認」「不確定」。以日英交替、人名漢字、音樂片頭測試；另測手選來源、雲端語言名稱轉標準代碼、以及中文輸出繁簡的映射。
- **驗收**：產出逐語言混淆矩陣、不確定率、首次鎖定耗時、誤切換次數與真正換語後延遲。確定性測例須通過「純靜音不算鎖定」「手選不被覆寫」「Whisper 中文提示及繁中 MT 目標各自映射」。以同份資料選擇策略並保存閾值和失敗例。
- **入口**：`HiddenTrackPlayer.tryLockLanguage / resolveSourceLangForWindow`、`AudioCaptureService.resolveAutoSourceLangForMt`、`LocalNllbEngine.toNllbTag`。依賴 LT-03、LT-09 的本機能力檢查。

### LT-11 讓慢速 ASR 或 MT 的漏句可以追蹤〔缺陷及壓測〕

- **重現**：連續餵 100 個帶編號的音窗，先卡住 ASR，再單獨卡住 MT，超過現有 16／24 的佇列容量。即使 DROP_OLDEST 淘汰舊元素，當次 trySend 仍可能成功。
- **驗收**：每個窗口能對帳到完成、無聲、失敗、取消或明確丟棄。可預讀影片在可控負載下暫停／放慢輸入，解除阻塞後所有有聲窗口均完成；即時擷取在過載時維持有界記憶體，報告丟失的媒體區間。測試收尾時待處理數為零；PCM 回呼維持可返回。
- **入口**：`HiddenTrackPlayer.windowChannel / translationChannel`、`AudioCaptureService.audioChannel`。依賴 LT-01。

### LT-12 比較斷句與上下文對翻譯正確性的影響〔品質實驗〕

- **實驗**：固定模型、裝置和語言。用 C 比較現有 1.5–5 秒獨立音窗與「少量重疊、只提交穩定子句、帶上一句原文上下文」；再用人工原文直接跑相同 MT。安排至少一個否定詞跨窗的句子，檢查是否翻反，以及重疊處有沒有譯兩次。
- **驗收**：同表列出人工原文 MT、兩個端到端版本的關鍵語意錯誤數、ASR CER／WER、重複率、覆蓋率、首句時間與 P95 字幕偏差。保留逐句例子；只有語意與延遲取捨符合產品需求才採用候選策略。
- **入口**：`WhisperAudioProcessor.checkAndEmitWindows`、`HiddenTrackPlayer.runAsrAndEnqueueMt`、`GptTranslationEngine.translate`。依賴 LT-01。

### LT-13 統一 NLLB 測試和 Android 的 tokenizer〔已核對資料〕

- **重現**：從 Android asset 與 v3 tokenizer 的 added_tokens 反查 Python 測試表。兩份皆為 zho_Hans＝256200、zho_Hant＝256201、zul_Latn＝256202；腳本目前把前兩者填成 256201、256202。
- **驗收**：測試腳本從受測模型載入 tokenizer。用同一句、同一模型版本比較 Python 與 Android 的來源 token 序列、目標語言 token、decoder 起始 token 和結束條件，逐項相等。覆蓋日文、簡中、繁中、英文；舊的中文映射必須讓測試失敗。
- **入口**：`test_nllb_quantized.py`、`LocalNllbEngine.loadLangTokenIds`。可獨立開工。

### LT-14 在真機選出可持續運行的模型〔效能實驗〕

- **實驗**：先固定人工文本比較同版 NLLB 浮點匯出、量化匯出與 Android 推論；再讓同一手機、相同 ASR 切窗跑至少 15 分鐘，對照 small-q8、medium-q8 和可用的浮點基準。雲端路徑另列，記錄每媒體分鐘請求量。保留語言 token、精度、模型檔識別、執行緒、裝置溫度或 thermal status。
- **驗收**：報各階段 RTF、實際每秒完成的媒體秒數、P95 延遲、最大積壓、漏句、記憶體及人工品質評分。比較頭尾各 5 分鐘是否降速。全段 CLI 的 CER 是相對另一模型的差異，另列人工標註分數。只有固定文字在浮點版正常、量化版持續劣化時才追該匯出的數值誤差。
- **入口**：`compute_cer.py`、`test_nllb_quantized.py`、Android App 的逐句紀錄。依賴 LT-01、LT-13。

## P2 擴展前的架構驗證

### LT-15 驗證真正直播的共同時間軸〔直播風險〕

- **實驗**：使用有音訊報時和可見時間碼的滑動 HLS 直播，讓兩個播放器在不同時刻加入，經歷播放清單更新和一次重新緩衝。記錄同一語句在兩個視窗的位置及對應媒體時間。
- **驗收**：同一句在視窗移動前後映射到同一媒體事件；更新播放清單不使舊字幕重新出現。分別報字幕對嘴誤差及畫面距 live edge 的延遲。外部 App 音訊擷取則量「語音結束到字幕顯示」耗時。先量出錯位，再決定是否更換解碼架構。
- **入口**：`DualTrackPlayer.checkAndCorrectDrift`、`WhisperAudioProcessor.computeCurrentPositionMs`。依賴 LT-01、LT-07。[Media3 直播時間軸](https://developer.android.com/media/media3/exoplayer/live-streaming)

### LT-16 確立播放工作與引擎的生命週期〔併發風險〕

- **重現實驗**：假引擎暫時阻塞 ASR 或 MT，依序測返回、立刻換片、服務停止，再釋放舊工作。另使兩個假 ASR 回應以相反順序完成。現有播放器 release 在呼叫執行緒 runBlocking 等候，ASR 的語言則由引擎共用的最後一次結果讀取。
- **驗收**：每筆原文、語言與時間戳隨同一結果返回。退出時主執行緒可繼續處理 UI；工作退出後才釋放對應的 native／ONNX 資源，且恰好一次。舊回應不得寫回新影片。覆蓋播放器與擷取服務，再用真機重複切換並檢查卡頓、crash 和記憶體趨勢。
- **入口**：`HiddenTrackPlayer.release`、`VideoTranslationActivity.onDestroy`、`AudioCaptureService.onDestroy`、`SpeechToTextEngine.detectedLanguage`。在增加並行窗口前完成。
