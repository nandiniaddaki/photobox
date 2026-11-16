# 📸 PhotoBox: System Design Lab

A small photo upload app where you can watch caching, TTL, cache invalidation, rate limiting and sharding work **live**.
Built with Java 21, Spring Boot, PostgreSQL and Redis. It deploys to Render for free.

---

## 🚀 Deploy steps (about 20–30 min, no coding needed)

### Step 1: Put the code on GitHub
1. Go to https://github.com and sign up or log in.
2. Click **+** (top-right) → **New repository**.
3. Name it `photobox`, choose **Public**, then click **Create repository**.
4. On the next page, click the **"uploading an existing file"** link.
5. Unzip the download. Open the `photo-app` folder, select **everything inside it** (`src`, `pom.xml`, `Dockerfile`, `render.yaml`, ...) and drag it into the browser.
   ⚠️ Drag the files **inside** `photo-app`, not the `photo-app` folder itself. `render.yaml` must sit at the top level of the repo.
6. Click **Commit changes**.

### Step 2: Deploy on Render
1. Go to https://render.com → **Get Started** → sign up with **GitHub**.
2. In the dashboard, click **New +** → **Blueprint**.
3. Select your `photobox` repo. If it isn't listed, click "Configure GitHub" and give Render access.
4. Give the Blueprint a name (e.g. `photobox`) and click **Apply / Deploy Blueprint**.
5. Render creates 3 services:
   - `photobox`: your Java app
   - `photobox-db`: PostgreSQL
   - `photobox-cache`: Redis (Key Value)
6. Open the `photobox` service and watch the **Logs**. The first build takes **5–10 minutes** because Maven downloads everything.
   When you see `Started PhotoApp`, it's ready.
7. Open the URL at the top (`https://photobox-xxxx.onrender.com`). 🎉 Anyone can open this link.

---

## 🧪 Experiments to try in the app
| Concept | What to do | What you'll see |
|---|---|---|
| **Cache (cache-aside)** | Refresh the page twice | 1st load: MISS, about 300 ms. 2nd load: HIT, a few ms |
| **TTL** | Wait 60 seconds without touching anything, then refresh | MISS again, because the cache key expired |
| **Cache invalidation** | Upload a photo | `INVALIDATE` in the log, and your photo shows up right away (no stale feed) |
| **Rate limiting** | Upload 6 photos within 1 minute | The 6th upload is blocked |
| **Sharding** | Look at the shard badge on each photo | Each photo lives in one table only. The feed reads both tables and merges them (scatter-gather) |
| **Browser cache** | Open DevTools → Network and refresh | Images load from "disk cache" |
| **Graceful fallback** | Suspend `photobox-cache` in Render | The app still works, but every load is a slow MISS, and the log shows `REDIS DOWN` |

---

## 🗺️ Where each concept lives in the code
| File | What's in it |
|---|---|
| `PhotoService.java` | ⭐ The main file: cache-aside, TTL, invalidation, rate limiter, Redis fallback |
| `ShardRouter.java` | `hash(id) % 2` decides which shard a photo goes to |
| `PhotoRepository.java` | SQL queries, scatter-gather, and the fake 300 ms slow query |
| `PhotoController.java` | URLs (`/`, `/upload`, `/photos/{id}`, delete) and the browser cache header |
| `schema.sql` | The 2 shard tables: `photos_0` and `photos_1` |
| `application.properties` | Settings like TTL, rate limit and the slow-query delay. Change them and experiment |
| `render.yaml` | Infrastructure as code: app, database and Redis in one file |

**Changing code:** edit a file on GitHub and commit. Render redeploys automatically.
Easy first experiment: in `application.properties`, change `FEED_TTL_SECONDS:60` to `10` and watch the cache expire faster.

---

## ⚠️ Free plan limits
- The app sleeps after 15 minutes with no visitors. The next visit takes about 1 minute to wake it up, then it's fast again.
- The free PostgreSQL database **expires after 30 days** and the photos are deleted. That's fine for learning.
- If the free Redis restarts, the cache becomes empty. That's normal for a cache, and the app keeps working.
- The app is public, so anyone can upload. Limits: 60 photos total, 2 MB each, 5 uploads per minute per user.

---

## 💻 (Optional, later) Run it on your laptop
1. Install Docker Desktop and run `docker compose up -d` in this folder.
2. Open the project in IntelliJ and run `PhotoApp.java`.
3. Open http://localhost:8080.
