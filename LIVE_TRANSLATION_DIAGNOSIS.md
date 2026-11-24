# Android 即時翻譯問題診斷

觀眾反映三個現象：字幕與聲音對不上、自動辨識音源語言不穩、翻譯常失準。本次檢視涵蓋基準 commit `02434f8` 的 Android 主播放器，另對照外部 App 音訊擷取服務。下列程式行為已由原始碼確認；各行為對特定手機或影片造成多少延遲與錯誤，仍待真機量測。重現素材、紀錄欄位與逐項驗收條件見[實測 tickets](LIVE_TRANSLATION_BACKLOG.md)。

## 先確認受測路徑

主播放器有兩個 ExoPlayer。可見軌播放影音，靜音隱藏軌提前解碼音訊，轉成 16 kHz 單聲道 PCM，再交給語音辨識（ASR）和機器翻譯（MT）。譯文寫進字幕儲存區，可見軌依播放位置查詢。檢視當時的 debug 設定兩階段皆為 CLOUD，走 whisper-1 與 gpt-4o-mini。另一台電腦的本機設定可能不同，應以該次啟動的 `PERF | initEngines` 紀錄確認。更換端上模型只會影響使用該引擎的路徑。[引擎選擇](app/src/main/java/com/liveTranslation/VideoTranslationActivity.kt)

## 字幕延遲與漏句

| 現象 | 已確認的行為與可能原因 | 驗證 |
|---|---|---|
| 開頭已經有人說話，字幕遲到 | 隱藏軌送出 PCM 就推進 decodedUpToMs。播放器初始要求約 2 秒解碼進度，等滿 6 秒也會開播；此時 ASR 或 MT 可以仍在排隊。翻譯端的建議提前量可到 20 秒，播放器又限為 10 秒。 | LT-03、05 分別阻塞語言偵測與翻譯，對照已解碼和已連續完成字幕的媒體位置。 |
| 字幕穩定偏晚，靜音還顯示上一句 | 字幕從音窗中點排起。10–15 秒的音窗即使提前算完，最早也在 12.5 秒才顯示。結尾至少延長 2.5 秒，查不到新句時還可能保留過期字幕 15 秒。 | LT-07 用有確切語句時間戳的假 ASR，分別量排程偏差和實際 UI 偏差。 |
| 拖曳後少一句，或越播越缺句 | 跳轉清空字幕後，隱藏軌跳到「落點＋提前量」，中間對白可能被跨過。ASR／MT 佇列滿時會淘汰最舊的工作，當次送入新工作仍可能成功。 | LT-06 測第 60 秒落點；LT-11 分別壓滿兩個佇列並對帳每個音窗。 |

媒體時間決定一句字幕屬於哪一幀，單調時鐘量排隊與運算耗時。對隨選影片，可以延後開播來累積已完成字幕；真正直播需要量觀眾距 live edge 的延遲。HLS 的播放位置相對於會移動的直播視窗，兩軌視窗起點不同是待驗證的錯位來源（LT-15）。[播放控制](app/src/main/java/com/liveTranslation/player/DualTrackPlayer.kt) · [字幕查詢](app/src/main/java/com/liveTranslation/player/SubtitleStore.kt) · [Media3 直播時間軸](https://developer.android.com/media/media3/exoplayer/live-streaming)

## 音源語言判錯

- **雲端 auto** 先累積至少 15 秒音訊才呼叫語言偵測。雲端實作沿用回傳 null 的預設方法，之後仍由各短窗辨識；UI 卻可收到已接受的 auto、信心 0。短片可能在第一個請求發出前就結束（LT-03）。
- **本機 auto** 用片頭 15–30 秒決定語言，累積時間包含音樂與靜音。鎖到具體語言後會沿用，可能影響換語影片。找不到預設多語模型時還可能回退到英文專用的 ggml-tiny.en.bin。應先記錄實際載入檔名，再測有效人聲、換語及不確定結果（LT-09、10）。
- **手動切換** 只更新播放頁 UI，現有播放器保留載入時的語言。修正前做語言對照實驗，必須選好語言再重新載入影片（LT-04）。[語言判定](app/src/main/java/com/liveTranslation/player/HiddenTrackPlayer.kt) · [本機模型載入](app/src/main/java/com/liveTranslation/ai/WhisperCppEngine.kt)

## 翻譯失準與量化實驗

1. **原文在第一階段已經變成英文。** 雲端 auto → en 會選音訊翻英端點，再把英文當原文交給 MT。雙語字幕宜保留原語逐字稿，再翻一次（LT-02）。[端點選擇](app/src/main/java/com/liveTranslation/ai/OpenAITranscriptionEngine.kt)
2. **斷句可能切掉關鍵語意。** 播放器約每 1.5–5 秒切窗，達上限就切；ASR 收到空 prompt，MT 每次只見當前文字。若否定詞落在下一窗，前一句可能翻反。用人工原文直接跑 MT，再與音訊端到端結果逐句對照，可定位失誤階段（LT-12）。
3. **引擎失敗也可能顯示原文。** HTTP 失敗、逾時、缺少本機 session 或推論異常都有回傳原文的路徑。觀眾看到原文時，要查請求狀態才能區分同語略過、合法同文翻譯與失敗（LT-08）。[雲端 MT](app/src/main/java/com/liveTranslation/ai/GptTranslationEngine.kt) · [本機 MT](app/src/main/java/com/liveTranslation/ai/LocalNllbEngine.kt)
4. **中文量化測試有已確認的語言 token 偏移。** Python 測試把簡中、繁中分別設為 256201、256202；受檢 tokenizer 的正確值是 256200、256201，而 256202 是祖魯語。Android 已動態讀表。用同版 tokenizer、相同 token 和人工原文做浮點／量化對照，才能歸因於匯出或精度（LT-13、14）。[Python 測試](test_nllb_quantized.py)

既有 Android 整段 CLI 測試中，small-q8、medium-q8、medium-fp16 的實時因子（運算秒數／音訊秒數）分別為 0.254、0.801、1.571。最後一組在該次測試中持續落後音訊。這些數字不能直接換算成 App 的短窗延遲，既有 CER 也是與另一模型的輸出比較。真機驗收需同步看人工逐字稿錯誤、已顯示句子的時間偏差、漏句比例與起播等待（LT-01、14）。[CER 計算方式](compute_cer.py)

## 維護與擴展順序

先保留雙軌與單一 Gradle module。HiddenTrackPlayer（873 行）同時管播放、偵測、佇列與字幕排程，適合先把「帶媒體時間的音窗 → 原文、語言、語句時間、譯文和處理狀態」抽成可用假引擎驗證的流程。現有 ASR 另讀引擎的最後偵測語言；若增加並行請求，文字和語言可能錯配。增加並行前也要測換片、取消與 native 資源釋放（LT-16）。

AudioCaptureService（1,110 行）可共用結果格式與語言映射，外部即時音源則保留過載策略。LocalNllbEngine（1,389 行）可依模型載入、tokenizer、ONNX 解碼拆分，先用 LT-13 固定輸入輸出。初次重現建議用同一支 8 秒日語影片，依序測手選日文 → 繁中、auto → 繁中、auto → 英文，分別記錄是否送出 ASR、MT 收到哪種文字，以及首句何時出現。[完整驗收步驟](LIVE_TRANSLATION_BACKLOG.md)
