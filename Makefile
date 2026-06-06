# LDAPPortal local-deployment helpers.
#
# Targets here exist to take the guesswork out of "is what I'm running
# actually the version I think it is?". Compose alone can leave a stale
# JAR in the image (Dockerfile COPYs from distribution/commercial/target/,
# which is host-built — so `compose up --build` repackages whatever JAR
# was already there) and Docker layer caching can skip `npm run build`
# if it decides the inputs look unchanged. These targets force the
# rebuilds so neither half of the app silently lags behind the other.
#
# Usage:
#   make redeploy            # The big hammer: rebuild everything, recreate containers.
#   make package-backend     # Just refresh the JAR (host-side `mvn package`).
#   make redeploy-fast       # Same as redeploy but with Docker layer cache.
#   make redeploy-frontend   # Only rebuild & redeploy the frontend container.
#   make redeploy-app        # Rebuild & redeploy just app + frontend.
#   make redeploy-minimal    # Tear down, bring back only app/frontend/db/oud1 pair.
#   make logs                # Tail the app container.
#   make down                # Stop & remove containers (keeps volumes).
#   make db-pull-from-fly    # Copy a Fly.io Postgres DB into the local stack.

.PHONY: redeploy redeploy-fast redeploy-frontend redeploy-app redeploy-minimal package-backend logs down help db-pull-from-fly

# Windows shell handling. GNU Make on Windows runs recipes through cmd.exe and
# ignores the environment's SHELL — so even launched from Git Bash, recipes run
# in cmd, which can't execute the ./mvnw shell script ("'.' is not recognized
# …"). DETECTING the recipe shell proved unreliable (choco's make reports a
# POSIX-looking $(SHELL) but still falls back to cmd at run time), so PIN it:
# force cmd.exe and use the batch wrapper cmd can run. OS=Windows_NT is set in
# both cmd and Git Bash, so this fires regardless of the launching terminal.
# (POSIX-only targets like `help`, which use grep/awk, won't run under cmd —
# use WSL, or run `make SHELL=/usr/bin/bash <target>` from Git Bash, for those.)
ifeq ($(OS),Windows_NT)
  SHELL := cmd.exe
  .SHELLFLAGS := /c
  MVNW := mvnw.cmd
else
  MVNW := ./mvnw
endif

# Default — print available targets.
help:  ## Show this help.
	@grep -hE '^[a-zA-Z_-]+:.*?##' $(MAKEFILE_LIST) | sort | awk -F'[:#][:#]?' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$NF}'

# Full clean rebuild. Does NOT use Docker's build cache, so npm-run-build
# and the Java build inside the JVM image are guaranteed to re-run.
# Slower (~3-5 min on a typical laptop) but bulletproof — if you've just
# changed code and want to be 100% sure the deployment reflects it,
# this is the target to use.
redeploy: package-backend  ## Full clean rebuild + recreate containers (the big hammer).
	@echo "==> Stopping containers (keeping volumes)..."
	docker compose down
	@echo "==> Building images (no cache)..."
	docker compose build --no-cache
	@echo "==> Starting containers (forcing recreate)..."
	docker compose up -d --force-recreate
	@echo
	@echo "==> Done. Hard-reload your browser (Cmd/Ctrl+Shift+R) to flush index.html."
	@echo "    Tail logs: make logs"

# Cache-friendly variant. Use when you've changed only one thing and
# trust Docker to detect it. Falls back to `redeploy` if you suspect
# anything's off.
redeploy-fast: package-backend  ## Cache-friendly redeploy. Falls back to 'redeploy' if anything looks off.
	docker compose up -d --build --force-recreate
	@echo
	@echo "==> Done. Hard-reload your browser if changes don't appear."

# Frontend-only redeploy. Useful when you've only changed Vue/CSS code
# and don't want to wait for a JAR rebuild.
redeploy-frontend:  ## Rebuild + recreate just the frontend container.
	docker compose build --no-cache frontend
	docker compose up -d --force-recreate frontend

# App + frontend redeploy. Refreshes the JAR, rebuilds both images
# (no cache) and recreates just those two containers — leaving the
# database and directory containers running. Use when you've changed
# backend and/or frontend code but don't want to disturb the rest of
# the stack.
redeploy-app: package-backend  ## Stop, rebuild & restart just the app + frontend containers.
	@echo "==> Stopping app + frontend..."
	docker compose stop app frontend
	@echo "==> Building app + frontend images (no cache)..."
	docker compose build --no-cache app frontend
	@echo "==> Starting app + frontend..."
	docker compose up -d --force-recreate app frontend
	@echo
	@echo "==> Done. Hard-reload your browser (Cmd/Ctrl+Shift+R) to flush index.html."

# Minimal stack. Tears down every running container (keeping volumes),
# then brings back only the essentials: the app, the frontend, the
# database and the OUD1 primary/alternate directory pair — nothing for
# the other vendors (OUD2/3, OpenLDAP, AD). Use when you only need the
# OUD1 topology up and want to free the resources the rest consume.
# This does NOT rebuild images; pair with redeploy-app first if you've
# changed code.
redeploy-minimal:  ## Tear down all, bring back only app/frontend/db/oud1 primary+alternate.
	@echo "==> Stopping all containers (keeping volumes)..."
	docker compose down
	@echo "==> Starting minimal stack (app, frontend, db, oud1-primary, oud1-alternate)..."
	docker compose up -d app frontend db oud1-primary oud1-alternate
	@echo
	@echo "==> Done. Minimal OUD1 stack is up. Tail logs: make logs"

# Rebuild the runnable JAR that the backend Dockerfile COPYs from.
# Skips tests so this stays fast — run the test suite separately when
# you actually want to verify behaviour.
#
# `clean` is load-bearing: an incremental `package` leaves resources that
# a previous build copied into target/classes even after their source is
# deleted/renamed. A migration removed or renumbered upstream (e.g. the
# schema rebaseline that folded V2__provisioning_profile_version.sql into
# V1__baseline) would otherwise stay in target/classes and ship in the JAR
# next to the new V2 — Flyway then aborts boot with "more than one
# migration with version 2". Cleaning first guarantees the JAR's migrations
# match the source tree exactly.
package-backend:  ## Rebuild backend JAR (skips tests).
	@echo "==> Building backend JAR (clean, skipping tests)..."
	$(MVNW) -DskipTests -q clean package

# Pull a Fly.io Postgres DB down into the local compose stack so you can
# develop against a copy of the deployed data. Delegates to the script,
# which tunnels via `flyctl proxy`, dumps with the local container's
# pg_dump (version always matches), and restores with --clean.
# Pick the edition with EDITION=c|ci|e (default ci); FORCE=1 skips the
# confirm prompt. See scripts/db-pull-from-fly.sh for all knobs.
db-pull-from-fly:  ## Copy a Fly Postgres DB into the local stack (EDITION=c|ci|e, default ci).
	./scripts/db-pull-from-fly.sh

logs:  ## Tail logs of the app container.
	docker compose logs -f app

down:  ## Stop & remove containers (keeps volumes).
	docker compose down
