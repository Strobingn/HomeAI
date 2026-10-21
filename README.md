# Desk

Desk is a chat app for your phone. The model runs on your desktop PC, on the NVIDIA GPU you already have. The phone only shows the conversation.

```
Phone  --Wi-Fi or Tailscale-->  PC port 8787 (Desk)  -->  Ollama on port 11434  -->  GPU
```

Ollama is already installed on the PC. Desk does not install it. It checks that Ollama is answering, keeps the model loaded, and streams each token to the phone so the reply shows up as it is written.

## Get the code

```bash
git clone https://github.com/Strobingn/HomeAI.git
cd HomeAI
```

## What you need

- The desktop PC (Windows or Linux) with the NVIDIA GPU and Ollama already installed
- Python 3.11 or newer on that PC ([python.org](https://www.python.org/downloads/))
- An Android phone
- The phone and the PC on the same home Wi-Fi, or both signed into Tailscale

## 1. Download the model (once)

On the PC, open a terminal and run:

```bash
ollama pull qwen2.5:7b
```

That tag is Qwen2.5 7B Instruct at Q4_K_M, about 4.7 GB. It is the default because speed matters on a 12 GB card: the weights leave several gigabytes free for the chat cache, and a 7B model produces tokens quickly. Desk keeps it loaded in VRAM so the next reply does not wait on a cold start.

Other models you can switch to later:

| Model | When to use it |
| --- | --- |
| `qwen2.5:7b` | Default. Fastest good chat model for this PC. |
| `llama3.1:8b` | Another fast 8B model, if you prefer Llama. |
| `qwen2.5:14b` | Smarter, still fits in 12 GB at the default quant, but slower and tighter on long chats. |

Check that Ollama is using the GPU:

```bash
ollama ps
```

The processor column should say 100% GPU after the first reply. If it says CPU, the NVIDIA driver is not visible to Ollama.

## 2. Start Desk on the PC

Leave this window open. Closing it stops the phone from connecting.

**Linux**

```bash
./start.sh
```

**Windows**

Double-click `start.bat`. If Windows asks about a firewall, allow Python on private networks.

The first start installs the server into `server/.venv` and writes `config/.env`. If the API key was blank, the window prints a line like:

```
API_KEY=paste-this-into-the-phone
```

Copy that value. It is also stored in `config/.env` if you need it again. Do not commit that file or send it to anyone outside the house.

The window then prints the phone URL, for example:

```
Phone URL on this Wi-Fi:  http://192.168.1.20:8787
```

Use that exact address, including `http://` and the port.

You can confirm the server from the PC itself:

```bash
curl -N http://127.0.0.1:8787/chat \
  -H "Authorization: Bearer YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"Say hello in five words.\"}]}"
```

Words should appear one piece at a time. `Ctrl+C` stops the curl command. It does not stop Desk.

## 3. Install the Android app

The project is a normal Gradle app. On a machine with the Android SDK:

```bash
cd android
./gradlew assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

Copy it to the phone (USB, email to yourself, or a shared folder). Open it. If Android blocks the install, allow installs for the app you used to open the file (Files, Chrome, or Gmail). The app is named **Desk**.

The debug APK is **Desk Dev** (`com.homedesk.chat.dev`). It installs alongside Desk so testing does not overwrite your release app or its chats. Debug keys belong to the build machine; APKs from different machines may require reinstalling Desk Dev. Use a privately signed release for dependable updates.

### Private release signing (one-time setup)

The old shared signing key was public and is permanently compromised, including copies in Git history. Builds no longer use it. Removing the file from the current branch does not revoke previously signed APKs.

1. Install JDK 17, with `keytool` on PATH.
2. From the repository root, run `python scripts/setup_signing.py`.
3. Privately back up the resulting `.homedesk` directory in your home folder. It contains the keystore and `signing.properties`; never commit or share these files.
4. Run `cd android`, then `gradlew.bat assembleRelease` on Windows or `./gradlew assembleRelease` on Linux.
5. Install `app/build/outputs/apk/release/app-release.apk`. An old Desk signed with the exposed key needs one uninstall before the first privately signed release. Uninstalling removes on-phone chats and settings. Keep any needed chat content before doing so.

All future release builds must use this same private identity. A newer versionCode then updates in place. Release packaging fails if private signing credentials are missing. For another build machine, securely restore the `.homedesk` directory or set `DESK_SIGNING_PROPERTIES` to its properties file.

### GitHub APK builds

Every push and pull request runs server tests on Windows and Linux, Android unit tests and lint, and uploads a **Desk Dev** APK under the workflow's artifacts. Open [Actions](https://github.com/Strobingn/HomeAI/actions), select the branch run, then download its Desk Dev artifact.

For privately signed release APKs, configure these [repository Actions secrets](https://github.com/Strobingn/HomeAI/settings/secrets/actions): `DESK_KEYSTORE_BASE64` (base64 of your private keystore), `DESK_STORE_PASSWORD`, `DESK_KEY_ALIAS`, and `DESK_KEY_PASSWORD` (values from your private properties file). Then manually run **Test and build Desk** on the desired branch. The release job requires these secrets and removes its temporary key afterward. Secrets cannot be configured through the connected repository API used for this change.

## 4. Connect the phone

1. Open Desk.
2. Tap **Open Settings** (or the Settings tab).
3. Server URL: the Wi-Fi address from the Desk window, such as `http://192.168.1.20:8787`.
4. API key: the value after `API_KEY=` in `config/.env`. The Show button reveals what you typed.
5. Tap **Test connection**.

You want a **Connected** card, and a line that says `qwen2.5:7b is loaded and ready.` The first test after a reboot can take a few seconds while the model loads into VRAM.

Then go Home and tap **New chat**. The reply streams onto the screen. Chats are stored on the phone.

You can always type the URL and key by hand. Settings does not depend on a QR code or automatic discovery.

## Use it away from home (Tailscale)

Do not open a port on your router.

1. Install Tailscale on the PC and the phone from [tailscale.com/download](https://tailscale.com/download).
2. Sign both in to the same Tailscale account.
3. Restart `start.sh` or `start.bat`. The window prints `Phone URL on Tailscale: http://100.x.x.x:8787`.
4. Put that address in the phone's Server URL. The API key stays the same.

If the window was already open, run `tailscale ip -4` on the PC and use `http://` plus that address plus `:8787`.

Desk listens on every interface (`0.0.0.0`), so the Tailscale address works without extra forwarding. Traffic stays inside your tailnet.

## Start Desk when you sign in

Run `start.sh` or `start.bat` once by hand first, so the API key and the Python environment exist.

**Windows**

1. Press Win+R, type `shell:startup`, and press Enter.
2. Right-click inside that folder, choose New, then Shortcut.
3. Point the shortcut at `start.bat` in this project.
4. Sign out and back in. Leave the Desk window open.

**Linux**

Create `~/.config/systemd/user/homedesk.service` and replace both paths:

```ini
[Unit]
Description=Home Desk
After=network-online.target

[Service]
WorkingDirectory=/home/YOU/desk/server
ExecStart=/home/YOU/desk/server/.venv/bin/python -m app.main
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

Then:

```bash
systemctl --user daemon-reload
systemctl --user enable --now homedesk.service
```

Ollama's own app should also start when you sign in. Desk only talks to it.

The model stays in GPU memory after you close Desk, because Ollama was asked to keep it loaded (`KEEP_ALIVE=-1`). The next start is then instant. To free the GPU:

```bash
ollama stop qwen2.5:7b
```

Set the PC so it does not go to sleep while you want the phone to reach it. A sleeping PC will not answer.

## Update an existing PC

From the project folder (`C:\Users\Austin\HomeAI`):

```bat
git pull
```

Restart Desk so it installs the new Python packages and picks up the file settings.

- If `start.bat` is open in a window, close that window and double-click `start.bat` again. The script installs anything new from `server\requirements.txt` (including `python-multipart`).
- If Task Scheduler starts the gateway, open Task Scheduler, select the HomeAI Gateway task, choose End, then Run. That task should point at `start.bat`.

The phone URL and the API key do not change. Install the new Desk APK over the old one.

File access uses the same port, **8787**. You do not add a firewall rule for a new port. If the phone already connects, leave the firewall alone. The Windows note below is only for a PC that has never been allowed through.

## Files on the PC

The Files tab reads and writes only the configured folders. A blank `ALLOWED_ROOTS` limits access to the `Desk` folder in your PC account's home directory; the server creates it automatically.

`config/.env`:

```
ALLOWED_ROOTS=
MAX_UPLOAD_MB=200
MAX_TEXT_BYTES=1000000
CODER_MODEL=qwen2.5-coder:14b
```

Add the folders you actually want to work on, separated by semicolons, then restart Desk:

```env
ALLOWED_ROOTS=C:\Users\Austin\Desk;D:\Projects;C:\Users\Austin\Documents
```

Explicit folder settings are preserved on upgrade. Older versions automatically wrote `ALLOWED_ROOTS=ALL`; that value now falls back to the home `Desk` folder unless you also explicitly set `ALLOW_ALL_DRIVES=1`. Add your actual project folders to `ALLOWED_ROOTS` to use them. To deliberately enable every drive accessible to the server process, set both `ALLOWED_ROOTS=ALL` and `ALLOW_ALL_DRIVES=1`. API-key holders have read/write access inside the configured roots. The API key itself stays in `config/.env`; do not place that configuration folder inside a shared root.

On the phone:

1. Open the Files tab. The first screen is the allowed folders (or drives if explicitly enabled).
2. Open a drive, then a folder. Upload picks files on the phone (more than one is fine) and sends them to that folder, with a progress bar.
3. From another app, choose Share, then Desk. Pick the folder on the PC and upload.
4. Tap a text file to read it. You can edit it yourself and tap Save. You do not have to use the model.
5. Ask AI reads the file on the PC, streams a reply, and shows a preview of the proposed file. Nothing is overwritten until you tap Save. Save writes a `.bak` copy of the previous file beside it.
6. In a chat, the attach button adds a PC file as context for the next message. That does not change the file.
7. Delete moves the item to a `.trash` folder on that drive. It is not a permanent delete.
8. Download saves a PC file onto the phone.

Code files use `qwen2.5-coder:14b` when `ollama list` has it, and `DEFAULT_MODEL` otherwise. A file that is too big for the model's context can still be edited by hand. The screen says when the PC is offline, the API key was rejected, the file is too large, or the path is outside the allowed drives.

## Work on a whole folder in chat

1. Add your project folder to `ALLOWED_ROOTS` as above and restart the PC gateway.
2. On the phone, open **Files**, navigate into the folder, and tap **Chat with this folder**. You can also use the attach button inside a chat and choose **Use this folder**.
3. Ask a question or request changes across files, for example: “Explain how this project starts” or “Update the API response and its Android client together.” The folder remains attached for follow-up messages and is remembered with the conversation.
4. Tap the coverage card to see which files the model received. Each question rescans subfolders and ranks text files by the question. Mention a specific relative path to focus the next response, or select a smaller subfolder.
5. When the model proposes edits, tap **Review changes**. Review each file's diff or complete new contents, then **Save all changes**. Chat alone never writes files.

Folder chat can replace multiple existing text files and create new text files, including subdirectories. It does not execute commands, install packages, delete files, or rename files. Saves check the original contents first, refuse stale proposals, and write `.bak` copies for existing files. A failed multi-file save attempts to restore files already written; this is not an atomic transaction against PC crashes or simultaneous external file edits. Proposals expire after 30 minutes or a server restart; ask again if one expires. A repeated save of the same completed proposal does not write twice.

Context is bounded: up to 1,000 indexed text files, 10,000 scanned entries, 2 MB of source read per request, 100 KB per source file, and a smaller selection fitted to the model context. The UI reports omitted/skipped files and capped listings. The entire folder does not have to fit in the model at once. Folder chat uses at least an 8,192-token context, so it may use more GPU memory than ordinary chat. `NUM_CTX` can raise this up to 32,768, subject to model/GPU limits. Token budgeting is approximate; smaller folders or fewer changed files work best on a 7B model.

Hidden files/folders, common dependency/build folders, signing material and common credential filenames are excluded. Add a `.deskignore` file at the workspace root for extra glob patterns, one per line, such as `data/*` or `*.log`. Comments start with `#`; rules are exclusion-only, not full gitignore syntax. File contents are sent only to the configured Ollama endpoint. Arbitrarily named files can still contain secrets: choose appropriate workspace roots and ignore rules.

The model's proposal format is one fenced `desk-changes` JSON array containing objects with relative `path` and complete `content`. Invalid or incomplete proposals cannot be saved. At most 12 changed files are accepted per proposal. A file omitted from context cannot be overwritten; ask again mentioning its path.

## Change the model

In the phone: open a chat, tap the model name at the top, or use Settings. Pick a model from the list, or type a name and tap **Use this model**. Typing a name always works, even when the list has not loaded.

That choice is saved on the phone and sent to the PC, which loads it and keeps it warm. The PC keeps one model loaded so replies stay fast.

You can also change the default before the first start by editing `DEFAULT_MODEL` in `config/.env`. `NUM_CTX` is the context length. `4096` is the speed setting. Raise it if long chats start to forget the beginning.

## Troubleshooting

**The phone says it can't reach the desk.** The Desk window must still be open. Use the PC's Wi-Fi or Tailscale address. The app adds `http://` and port `8787` to local addresses when omitted, and accepts a copied Phone URL banner line. It rejects phone loopback addresses and Ollama's port `11434`. The phone must be on the same Wi-Fi as the PC, or on Tailscale. Some guest networks block phones from seeing computers. Use the main Wi-Fi.

**API key was rejected.** Copy the value after `API_KEY=` again. Do not include the quotes or the name `API_KEY`.

**Ollama is not running.** Open the Ollama app, or run `ollama serve`. Desk does not install Ollama.

**The model is not downloaded.** Run `ollama pull qwen2.5:7b` on the PC.

**Port already in use.** Another Desk window is open. Close it, or set a different `PORT` in `config/.env` and use that port in the phone URL.

**Windows firewall.** Desk still listens on port 8787 only. Allow Python on private networks. You do not add a second rule for files. If you dismissed the prompt:

```bat
netsh advfirewall firewall add rule name="Home Desk" dir=in action=allow protocol=TCP localport=8787
```

**Linux says ensurepip is not available.** Install the venv package, then run `./start.sh` again. On Ubuntu: `sudo apt install python3-venv`.

**Linux firewall**, only if you use ufw:

```bash
sudo ufw allow 8787/tcp
```

## How the server is built

The gateway is Python and FastAPI. A small process is simpler to start on both Windows and Linux than a compiled binary, and the GPU is what limits reply speed. The server streams Server-Sent Events. The phone uses OkHttp with a connection pool so the next message does not pay for a new handshake.

Every chat request sends Ollama `keep_alive: -1`, and startup loads the selected model with an empty message list (weights only, no extra generation).

Endpoints, all except `GET /` require `Authorization: Bearer <API_KEY>` (or `X-API-Key`):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Plain check that the process is up |
| GET | `/health` | Ollama, selected model, whether it is loaded |
| GET | `/models` | Models Ollama has downloaded |
| GET | `/model` | The model Desk will keep warm |
| PUT | `/model` | Select a model and load it |
| POST | `/chat` | Stream a reply as `text/event-stream`. Optional `file` attaches a PC file as context and does not save it. |
| GET | `/files/roots` | Drives, or the folders in `ALLOWED_ROOTS`. Detected on each call. |
| GET | `/files/list` | One folder. Query: `path`. |
| GET | `/files/read` | A text file as JSON. |
| GET | `/files/download` | The file bytes. |
| POST | `/files/upload` | Multipart upload into a folder. Limit `MAX_UPLOAD_MB` (200). |
| POST | `/files/folder` | Create a folder. |
| PUT | `/files/write` | Save text. Writes a `.bak` of the previous file first. |
| POST | `/files/rename` | Rename inside the same folder. |
| POST | `/files/delete` | Move to `.trash` on that drive. |
| POST | `/files/ai` | Stream a proposed new version of a text file. Does not save it. |
| POST | `/workspace/chat` | Stream folder coverage, chat tokens, and a multi-file proposal. Body: `folder`, `messages`, optional `model`. |
| POST | `/workspace/apply` | Save a reviewed proposal. Body: `proposal_id`. Requires unchanged original files. |

## Tests

From the `server` folder, with Python 3.11+:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
.venv/bin/pytest
```

On Windows, use `server\.venv\Scripts\python` and `server\.venv\Scripts\pytest` the same way.

The tests stand in for Ollama, so they do not need a GPU. Android unit tests run with:

```bash
cd android
./gradlew test assembleDebug
```

