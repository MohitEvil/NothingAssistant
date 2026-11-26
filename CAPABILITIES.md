# Nothing Assistant: what it can control

Everything here works without root. "Needs" says what you must grant (Settings → Phone control access).
When something is missing, the assistant says so and opens the exact Android page.

## Works out of the box
| Say | Does |
|---|---|
| open / launch <app> | Opens it. Close-sounding names ask "Do you mean …?" first |
| flashlight on/off, torch | Torch |
| timer 5 minutes, alarm 7 am, show alarms | Clock app |
| call / dial <name or number> | Dialer (fuzzy contact match asks first) |
| text <name> saying … | Messages draft, always confirmed |
| volume up/down/50%, mute | Media volume |
| play / pause / next / previous | Media keys |
| battery, time, date | Info |
| search …, navigate to … | Web / Maps |
| open <site>.com | Browser |
| email … saying … | Email draft |
| add event <title> | Calendar editor prefilled |
| change wallpaper | Wallpaper picker |
| app info <app>, uninstall <app> | App details / system uninstall prompt |
| copy <text>, read clipboard | Clipboard |
| how much storage / RAM, am I online, is Wi-Fi/Bluetooth/airplane/DND on | Status read-outs |
| open <name> settings (about 40 pages: display, sound, battery, location, security, privacy, apps, default apps, accessibility, date, language, keyboard, NFC, hotspot, VPN, cast, developer options, about phone, storage, notifications, accounts, sync, printing …) | Opens that page; near-miss names ask first |
| Wi-Fi / Bluetooth / internet | Opens the Android panel (apps cannot flip them) |

## Needs one-time access
| Say | Needs |
|---|---|
| brightness up/down/60%/auto, auto-rotate on/off, screen timeout 2 minutes | Modify system settings |
| do not disturb on/off, silent / vibrate / normal mode, ring/alarm/notification/call volume | Do Not Disturb access |
| lock the phone, turn off the screen | Device admin (lock only) |
| read my notifications, clear notifications | Notification access |
| what's on my calendar | Calendar permission |
| call/text by name | Contacts permission |

## Fuzzy matching
Apps, contacts, settings pages and "app info/uninstall" targets use spelling similarity.
If the match is not exact, the assistant asks "Do you mean …?" and only acts after yes.

## Deliberately not included
- Factory reset, wipe, password reset (device-admin policies that can destroy data).
- Direct Wi-Fi / Bluetooth / mobile-data / airplane toggles (blocked for apps since Android 10 without root).
- Silent screenshots, other apps' data, USB debugging toggles.

## Ideas for later
Call-log queries (READ_CALL_LOG), location sharing, usage-access "screen time" report, shortcuts to routines
("good night" = DND + brightness low + alarm), accessibility-service gestures (back/home/recents/screenshot).

## v-next: wider commands, background screen control, offline companion
- Natural phrasing: "can you open wifi", "turn on developer options", "switch off bluetooth", Hinglish ("wifi chalu karo"). Toggles, settings pages and apps are resolved with fuzzy matching; AI is never used for these.
- Toggles (Wi-Fi, Bluetooth, mobile data, hotspot, NFC, location, airplane, battery saver, auto-rotate, etc.) are driven through the Settings page by the Accessibility service using node actions (not gestures), so your touches don't cancel them.
- Developer options: unlock (Build number x7) and toggle; "set smallest width to 444" jumps straight to the field.
- Global actions: back, home, recents, notifications, quick settings, power menu, split screen, lock, screenshot. Plus tap/type/scroll/read screen, force stop, clear cache, play music, reminders.
- Offline companion: greetings, moods, jokes, facts, riddles, stories, maths, coin/dice, "my name is X". AI is used only for real questions or knowledge.
Limits: Accessibility must be enabled manually (Access screen); switch labels vary by OEM; Wi-Fi/Bluetooth cannot be toggled directly on Android 10+ without root.

## Smarter matching, screen awareness, AI Voice (latest)
- Speech clean-up: "wi-fi / why fi / wi fi" all become wifi, "d p i" -> dpi, "developer option(s)/dev options/developer mode" -> Developer options, "four four four" -> 444. The recogniser's other guesses are tried if the first isn't a command.
- Settings and apps are matched by sound and spelling, ignoring plurals and filler words ("bluetooth settings", "developer option"). Near-certain matches open directly, doubtful ones ask "Do you mean...?".
- "Find X" opens an app/setting, or offers a web search if nothing is found. "Where is X / how do I find X" and "what can you do" are answered offline.
- Leaving: "go back", "go home", "exit this screen" (Back), "close the app / exit" (Home; apps can't be killed without root).
- Screen awareness (Accessibility + on-device OCR): "what's on screen", "what can you see", "find the search bar", "tap search", "click Login". If an app exposes no text, a screenshot is read with on-device OCR.
- Gentle declines: no mention of keys or AI unless AI is on and set up.
- Settings > AI settings has two tabs: AI Chat and AI Voice (cloud text-to-speech with OpenAI, Groq, ElevenLabs or custom). Both are OFF by default; the offline voice is the fallback.

## Gestures, chains, stay-open, camera (latest)
- Gestures: long press / press and hold / tap and hold <thing>, double tap <thing>, swipe left/right/up/down, zoom in/out, select all / copy / paste / cut, scroll to top/bottom, press enter. "click it" repeats the last target.
- Chains: "open settings and search developer options", "open telegram and message demon hi", "send hi to demon on whatsapp", "turn off wifi and turn on bluetooth". In-app search and messaging find the search box/message box and Send button on their own. If a step fails, the assistant says which step it got stuck on and waits for your next instruction.
- Stay open between tasks (Settings > Stay open between tasks, on by default): after each task the window comes back and listens for the next one. Say ok, thanks, stop it or stop listening to close. Needs "Display over other apps" so the window can return over other apps.
- Camera: "take a picture / selfie" opens the camera and presses the shutter (selfie flips to the front camera and waits 1.5 s). With the camera already open, "take a picture" or "tap shutter" presses it.
- AI voice: FETCH MODELS and FETCH VOICES, and a Google (Gemini TTS) provider.

## Careful screen control (new)
- Taps only on an exact name; otherwise it searches the whole list, then asks "Do you mean X?" before touching anything.
- Several matches (toggles, same-named chats) -> it lists them and asks which; answer "the second one", "the top one", "the last".
- Ordinals and areas: "tap the second toggle", "long press the top right button", "click the last one".
- Relations: "click the stop button in front of Telegram", "... below Telegram", "what's right after that", "which button is above it".
- Short-term memory (last 5 things, ~5 minutes): "can you see the Demon chat?" then "click on it", "long press it", "delete it", "what's next to it".
- Scrolling: scroll left/right, "long scroll down", "scroll up 5 times", "keep scrolling down", "scroll until you see X".
- Switches: "toggle on", "turn it off", "enable <label>", "switch the second one on".
- Also: hide keyboard, refresh, next/previous page, "do it again", "type hello in the search box".
- AI voice: Gemini/OpenAI/ElevenLabs are streamed (speech starts on the first chunk); Groq/custom download first.

## Notifications, weather, typing (new)
- Needs Notification access. "Show all notifications" reads each one aloud; "read the second notification", "open the last notification", "reply to the first notification saying ok", "reply to Demon saying I'm busy" (uses the notification's own reply box), "clear notifications from Telegram", "did I get anything from YouTube", "what's Demon saying", "who needs my reply".
- "What's the weather (in Delhi / tomorrow)": Open-Meteo, no key; without a place it uses your network's rough location.
- "Type 123 / type hello" opens the search box or input itself when none is focused. "Type X in the search box" also works.
- "Click the third video / photo / item / last notification": ignores toolbar icons, picks real content.
- Gemini 3.x AI voice only: <chuckle> for laughs and IPA for Indian place names and acronyms.

## Search, play, install, notification follow-ups (new)
- "Search X on Instagram / Telegram / any app": opens that app and uses its own search box. "Search X" alone uses the search box of the app in front. The web is used only for "search X on Google / the web" (or when nothing on screen can search).
- "Play X on Namida / Glass Player / Mochi / Spotify": opens the app, searches X, plays the first match. "Play a random song on Namida": opens it and shuffles. "Play X on YouTube": searches YouTube and opens the first video. Naming an app means it is a music/video app.
- Settings > Default music app / Default video app: used when you say "play a song" or "play a video" without an app.
- "Install Instagram": opens the Play Store search, opens the app page and presses Install.
- Notifications: "any new notification" -> "7 notifications: 3 from Telegram, 2 from WhatsApp". Then "what's new from Telegram", "who sent them", "what do they say". If a person messaged you it asks "Do you want to reply to Demon?"; say yes, then what to say, and it sends it.
- "Notification history" opens Android's history page; "read my notification history" reads the assistant's own log (kept from when Notification access was granted).
- "Keep scrolling down", "long scroll", "scroll down 5 times" now stop only at the real end.

## Navigation & maps
- "Navigate to X", "take me to X with Waze / Google Maps / any nav app", "walking directions to X"
- "Find hotels / parks / schools / markets / pharmacies / petrol pumps near me" (or "near Connaught Place") – speaks the closest ones with distances and opens the map
- "How far is X from Y", "how long to reach X (walking / by bike)", "best route to X", "shortcut to X"
- Allow Location in the Access screen for exact results; otherwise the network's rough area is used.

## Messages by dictation
- "Send a message to Demon on Telegram" → asks "What should I say?" → sends what you say ("cancel" stops it).

## Scheduled tasks (Tasker-style)
- "Open YouTube every day at 10 AM", "news briefing every morning", "every morning except Sunday", "only on Sunday at 8 pm", "on weekdays / weekends", "tomorrow at 7 say good morning", "in 30 minutes turn off Wi-Fi", "on 25 December at 9 AM …"
- "Remind me to drink water every day at 11" is spoken aloud at that time.
- Manage: "what scheduled tasks do I have", "delete the news automation", "delete all automations", "pause / resume automations".
- "Keep the screen awake for the next 6 hours" / "stop keeping the screen awake" (notification with a Stop button).

## News & web answers
- "Any new news?", "latest news about cricket", "news briefing" → ~10 different headlines.
- With AI chat off, other questions are answered from the web (DuckDuckGo / Wikipedia).

## Tool calling (Needle 3) and cross-app jobs
- Sentences the fixed grammar doesn't know go to tool calling: Needle 3 on the phone (when the GitHub build downloaded it), else your AI provider. Several tool calls become one chain across apps.
- "Order biryani from Swiggy", "book a cab to the airport", "book a table at Barbeque Nation": opens the app, searches, picks the first match and stops before paying.
- Open-ended jobs ("do this in the apps: …") use your AI provider to read the screen and tap step by step. It always asks before anything that pays or confirms.

## Advanced access (optional)
- Settings → Advanced access: Auto / Off / Shizuku / Root. Used only when granted; otherwise everything falls back.
- With it: instant Wi-Fi / data / Bluetooth / airplane / NFC / location / dark mode switches, force stop and clear caches without opening Settings, "clear data of X" (asks first), "revoke location from X", "turn off animations", "close background apps", "restart the phone", "run command …" (asks first).
- Without it: "audit location permissions", "how much storage is left", "do not disturb until 7 am / for 2 hours" still work.

## Assistant trigger
- The app is registered as a digital assistant, so the power-button / corner-swipe gesture opens the small panel and listens. Choose Nothing Assistant under Settings → Apps → Default apps → Digital assistant app.
- Starting a song or video stops listening and closes the panel.

## Hybrid brain (Nov 18)
Order for every spoken request: Needle 3 (on the phone) -> your cloud AI (only if switched on; it also sees the recent chat and what is on screen) -> fixed grammar -> plain answer.
- Needle/cloud can plan several steps: "open Play Store, wait 10 seconds, search Telegram and install it", "turn on the flashlight, wait 10 seconds and turn it off", "take a picture and send it to X on WhatsApp".
- The grammar handles the same chains offline (wait N seconds, "turn it off", "install it"), and wins over an unsure Needle guess that understood less.
- Chat history is saved on the phone (45 min of context for follow-ups). Say "clear chat history" to wipe it.
- Calls: direct with Phone permission, else through Shizuku/root, else the dialer + auto-press Call.
- Web answers: follow-up aware ("and its value?"), ranks Wikipedia, DuckDuckGo and Bing snippets by your key words and numbers.
- On-screen matching: closest match is used directly (no "did you mean?" dead end); "change it to X" picks X on the open list.

## Nothing Assistant 1.1
- Routing tiers: (1) Kotlin intents and system APIs, (2) the Kotlin fast router for single taps/scrolls with fuzzy + synonym matching, (3) Needle 3 -> cloud AI -> grammar chain for multi-step or unclear requests, with the Screen Agent loop (GOAL / HIST / UI, max 10 steps, screen-change detection) for open-ended jobs.
- Guardian: taps on Pay, Send money, Transfer, Confirm order, Delete, Uninstall and similar always ask "Say confirm to tap ..." first.
- Play Store: "install X" / "update X" opens the right page, presses only that app's button, waits and reports progress; asks before updating an app that is already installed.
- Radio lists (like Default digital assistant app) are verified after the tap and retried like a finger if the choice didn't change.
- Follow-ups: "what about X", "tell me more", "say that again", "what did I ask" use the saved chat history.
- The recogniser's start/stop dings are muted for the whole listening turn and restored afterwards.
- Matcher: Jaro-Winkler, Levenshtein, a synonym table and a small trigram embedding stand in for a neural sentence embedder (no extra native library, so it can't clash with the voice engine).

## Smarter assistant (latest)
- **Summaries**: offline summariser used for web answers (AI chat off), "summarise this screen / my messages", and to squeeze old chat turns for the AI and Needle.
- **Memory**: a blackboard of what just happened (last screenshot, last app, last switch and its previous value) so "delete it", "undo that", "turn it back off", "did it work?" work without a model.
- **Self-learning skills**: plans from Needle 3 or your AI that worked are saved and replayed offline next time ("forget skills" clears them).
- **Messages**: "what's my latest SMS", "read my last 3 texts", "texts from Mom" (needs SMS access).
- **Screenshots**: "delete the screenshot you just took / latest screenshot" (Android asks once to allow the delete, unless Shizuku/root).
- **Default assistant**: one-tap button in Phone control access.

## Look and feel (latest)
- Settings now has sections: Voice (with language and speech recognition), AI settings, Appearance, Access and permissions (phone control, app permissions, Shizuku/root).
- Appearance: theme, Nothing / Liquid glass / Minimal glass, accent colour, dot blob, typing text, animations, battery saver.
- Dot-matrix icons, squishy press animation, dot blob while it listens/thinks/works, words typed as it speaks, animated dots on status lines.

## Latest round
- Dot blob is now a 3D liquid sphere: rotates, ripples, lit with a highlight, reacts to voice level; sweep while thinking, orbit ring while executing.
- Liquid Glass look: drifting soft light blobs behind the glass, diagonal sheen and brighter rim. Nothing and Minimal Glass unchanged.
- Plan guard: a tool call from Needle or the cloud AI must be backed by words in what you said (no more "set default assistant" turning into a Google update).
- Set default assistant to Google or any other app has its own command; Play Store waits are bounded.
- Follow-ups: "and X", "X too", "do it again", answers to "Which app?", he/she questions after web answers.
- Casual chat works without AI: chatter, moods, reactions and complaints get a conversational reply, never a web search; replies use the last turn ("why?", "really?", "explain").
- Web and AI answers are cleaned of HTML codes like &#x27;.

## Speech recognition choices (Voice settings > Recogniser)
- Auto: Google/system speech when the ROM has it, otherwise my own on-device recogniser (it downloads its model by itself the first time).
- Google / System, On-device Live (streaming Zipformer, words appear as you speak), On-device Accurate (Silero VAD + Moonshine), Two-pass (live words, then Moonshine re-reads for the final text).
- Models download once from the Voice page and then work with no internet. They run through sherpa-onnx, already in the app for the voice.

## Automatic permissions
- With Shizuku or root, permissions, overlay/settings/alarm/battery access, screen control and the default assistant are granted by themselves at start (switch and "Grant everything now" button on the Access page).
- "Set default assistant" uses the same route first and only opens settings if that isn't possible.

## Chat memory (ideas from PyBot, written in Kotlin)
- Remembers what you tell it on the phone only: "my favourite colour is blue", "I live in Pune", "I work as a nurse", things you like and hate. Ask "what's my favourite colour", "where do I live", "what do you know about me"; "forget what I told you" clears it.
- A hello in the middle of a chat is a check-in, not a fresh good-morning.

## Latest fixes
- Auto-grant now runs one permission at a time and writes progress down first, so an app restart caused by a permission change can't loop. The default assistant is no longer set at start-up, only when you ask.
- Offline answers from PyBot's knowledge (about 6,200 everyday questions) come before web search. PyBot is Apache-2.0.
- Blob: finer dots, much slower wave-like motion, smaller ripples.
- Default look for new installs: Dark, Nothing style, Red accent.
- Liquid Glass reworked to feel like iOS: clear body, bright edge on two corners, inner glow, floating shadow, colour blobs drifting behind.
- Speech: on-device decoder kept at full precision, quiet-mic gain, logging of what was heard. Marked beta.

## Round 3
- Blob: back to the original animation.
- Speech: the sherpa recogniser is gone. Vosk offline recognition replaces it (English India/US, Hindi; about 40 MB each, download in Voice settings). Auto uses Google speech when the ROM has it, else Vosk.
- Liquid Glass is now real frosted glass: the background is blurred live and each pane shows its own patch of it, with a smoked/frosted tint, bright top edge and soft shadow. Needs Android 12+ for the blur; older phones get the tint without blur.
- PyBot knowledge grew to about 7,400 answers.

## Round 4
- Speech: the app asks the phone's recogniser which languages it supports (Google if present) and sets en-US, else the phone language, else the first available, until you pick one. Voice settings shows what was detected, or walks you through installing Google's recogniser (Play Store page, Voice input settings, refresh). Vosk stays optional.
- Liquid Glass now uses the Haze library: the background lives in its own layer behind each screen and every pane blurs it live.
- Permission grants: fixed the Shizuku "process hasn't exited" error that made every grant step report failure.

## Round 5
- Liquid Glass: Haze effect now uses a transparent background colour (no fill), so only the blurred backdrop and the smoked tint show.
- Free AI: "KILO FREE" provider (api.kilo.ai gateway, model kilo-auto/free, no account or key, about 200 requests an hour). AI settings has a USE FREE AI button that turns AI chat on and puts it first. FETCH lists only the models Kilo marks free.
- Free online voices: "EDGE FREE" voice provider (Microsoft Edge Read Aloud, 350+ neural voices, no key). AI settings > AI Voice has USE FREE EDGE VOICES and FETCH VOICES (searchable). Falls back to the offline voice if the service fails.
- Phone voices: Voice page lists every voice the phone's speech engine has installed (auto-detected), with search, LOCAL / NEEDS INTERNET tags, a PLAY preview and AUTOMATIC.
- Live mode (Settings > Live mode): hands-free spoken conversation with transcript, pause/resume, tap to interrupt, typed messages, and the same commands and AI as the normal assistant.

## Round 6

- **Voice settings** is now a short list of pages: Speech recognition (with its own Offline languages page), Voices (phone voices, downloadable offline voices, online voices, speed), Wake word and Behaviour. Downloads live only inside the page they belong to.
- **Voice picker** (title + refresh, search, count, radio rows with name / gender / Multi tag, speaker preview, Done) is used for phone voices and for fetched online voices (Edge and other AI voice providers).
- **Languages on this phone**: only languages that are really downloaded are listed (Android 13+ on-device packs plus every Vosk model). Offline languages come from Vosk's online model list, so any language can be added.
- **Bilingual Vosk**: two models listen to the same audio and the more confident result wins (Settings: Understand two languages).
- **AI**: replies follow the voice's gender (detected from the Edge voice / speaker name, or set manually), including Hindi verb forms. New `power_control` tool (restart, shut down, recovery, bootloader); `run_shell` has full system access with a Guardian: harmless commands run, anything that deletes, resets or wipes asks first. Broader "restart / reboot / shut down" phrases (also Hinglish).
- **Screen vision**: for requests about the screen, and at every step of the screen agent, a screenshot is sent to the AI (toggle: AI sees the screen). "What's on my screen?" is answered from a screenshot.
- **Default assistant after restart**: re-applied at boot (BootReceiver, app start, accessibility connect) through WRITE_SECURE_SETTINGS (granted once by Shizuku/root) or Shizuku/root, only if you had chosen this app.
- **Speak to interrupt**: while a reply is spoken the microphone (echo-cancelled) watches for your voice; speaking over it stops the voice and listens.
- **Wake word**: optional foreground service, volume gate + phrase-only Vosk recogniser, training by recording the phrase three times, sensitivity.

## Round 7

- **AI agent** (`agent/AiAgent.kt`): for any job that needs the screen, the AI gets a numbered map of everything on screen plus a screenshot, picks one action at a time (tap by id/text/position, type, scroll, scroll-to-find, swipe, system buttons, open app, open settings page, search Settings, any intent, shell), checks whether the screen changed, and recovers (back, another route, Settings search). Never pays; asks before delete/reset. Used by `do_in_apps` and automatically when a normal tap/toggle/setting command fails.
- **Wake word** is now trained from your own voice (MFCC + dynamic time warping): a 12-prompt Siri-style setup (soft, loud, fast, slow, far, cheerful, sleepy...), live ring visual, test screen. Needs no speech model or download; any phrase, any language.
- **Voice speed**: long replies from Edge and other file-based voices start after the first sentence while the rest is produced in the background; follow-up listening starts sooner.
- **Blob**: smoothed voice response and Siri-style aurora lights.
- **Defaults**: Kilo (free AI) and Edge (free voice) are switched on once automatically.
- **Window fix**: the panel no longer closes between tasks, and if the assistant ever listens with no window it brings the panel back or stops listening.
- Vosk catalog also lists the more accurate big models.

## Round 8
- **Voice Match** (Voice settings → Wake word): Google/Siri-style setup. Intro, "How it works", then your phrase 5 times and 7 read-aloud sentences. Words light up as they are recognised (offline recogniser if downloaded, otherwise by speaking time), a colour wave follows your voice, each step is checked off, then "You're all set" with a live test showing a Voice Match %.
- **Speaker check** (`audio/Voiceprint.kt`): learns the cepstral shape and pitch of your voice; the wake word only fires for you. "Only my voice" toggle in Voice settings.
- **Blob** follows the voice: deep sounds swell the lower half, high sounds the top; it turns only as fast as you speak (`audio/VoiceBands.kt`).
- **Window**: `service/WindowAnchor.kt` keeps a 1-pixel invisible window while the assistant works so the panel can always reopen; follow-up listening never starts without a visible panel.
- **AI agent**: retries, longer timeout and real error messages; screenshot only when needed to save rate limit.
- **Routing**: with an AI on, anything longer than a short clear command is planned by the AI first; Needle 3 and the fixed grammar remain the offline fallback.

## Round 9
- **Online-first speech**: with internet, Auto uses Google's online recogniser (best accuracy, takes a second language via EXTRA_ADDITIONAL_LANGUAGES). Offline: Google's downloaded language if present, else Vosk. A network failure mid-turn falls back to offline for the same turn. "Offline only" toggle forces on-device.
- **Bilingual**: second-language chooser (Auto / None / any); a retry after "didn't catch that" listens in the other language.
- **Detection**: installed Google offline languages are read from three routes (speech service, on-device recogniser, default) and merged, cached and shown.
- **UI**: Voice → Speech recognition now has separate Google and Vosk sections.

## Round 10
- Google recognition now tries every route to the recogniser (Google speech service by name, on-device recogniser, phone default) and moves to the next if one fails; offline turns that Google can't do fall to Vosk. Each step is written to the log under ASR.
- ADD / MANAGE opens Google's voice settings (offline speech packs), not the default assistant page.
- Workflow `.github/workflows/build.yml` now includes the SDK 37 platform path fix.

## Round 11
- Google online recognition answered in English only: Google's recogniser ignores the chosen language unless `android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES` is sent (even empty). It is now always sent (correct key), plus the short spelling, plus Android 14+ language detection limited to the chosen languages when two are on.

## Round 12
- From the device log: the speech service found by name failed often (errors 13, 11, 3) while the phone's default recogniser heard Hindi correctly. The default is now tried first, and the route that last gave an answer is remembered and tried first next time.

## Round 13: welcome, answers, quick answers, stability
- Welcome setup on first launch: name, microphone, alerts, overlay, accessibility, default assistant and Voice Match, one step at a time, each skippable, with live "allowed" status that refreshes when you return from system settings. Updating users skip it.
- After every spoken answer the card stays on screen: COPY, SHARE, SAY AGAIN, and follow-ups (tell me more, simpler, shorter, in Hindi). A SPEAK AGAIN button appears after a result or failure.
- Idle screen: greeting by time of day, four tappable example commands that change through the day, and a box to type a request.
- New instant offline answers: unit conversion (length, weight, volume, temperature), dice, random number between two numbers, days until a date.
- Crashes are written to the log before the app closes.

## Round 14: faster planning, redesigned setup
- Fast mode for the planner and the screen agent: Thinking is turned off for these calls, replies are capped short, and the wait is 14 s instead of 30 to 75 s, so a slow model no longer holds a request for a long time. The cloud plan is also abandoned in favour of the on-phone plan if the model does not answer.
- The screen agent can send up to 4 safe steps in one reply (for example open an app, then tap a label, then type), so there are fewer AI round trips. It looks again after any step that fails or changes nothing.
- The agent waits less after each tap (it polls the screen every 0.12 s), takes screenshots only when the screen is sparse or the last step changed nothing, and sends them smaller. Password fields are never read out to the AI (idea taken from the AndroidHarness assistant).
- Setup redesigned: animated pixel-art picture for every step, segmented progress bar, fades between steps, ALLOWED / NOT YET / REQUIRED chips, and a summary of what is on at the end.
- New idle suggestions: summarize this screen, explain what's on my screen, help me reply to this message.

## Round 15 — Whisper hearing, memory, premium icons
- **Whisper speech recognition** (Groq, free key): records the sentence, finds the end by silence, sends one small WAV. Far more accurate for accents, Hinglish and names. Falls back to the normal recognisers for that same turn if the network, key or rate limit fails, and rests for a minute. Settings → Voice → Speech recognition → Whisper.
- **Memory that grows**: the assistant keeps lasting facts you tell it ("my sister is Riya", "I'm vegetarian"), explicit "remember that…" notes, and the apps you open most. A short ranked block goes into the AI's instructions; names also feed Whisper's vocabulary. Say "what do you know about me", "forget …", "forget everything about me". Persona page → Memory lets you switch it off, remove items or clear all. Stored only on the phone.
- **Edge voice retries** for 429, 5xx and empty audio, with a short back-off.
- **New icons**: stroke icons from the Lucide family (same as AndroidHarness) replace the dot icons. They draw themselves on stroke by stroke, and the welcome icons breathe and glow. Each setting and page has its own icon.
