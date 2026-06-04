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
#   make logs                # Tail the app container.
#   make down                # Stop & remove containers (keeps volumes).
#   make db-pull-from-fly    # Copy a Fly.io Postgres DB into the local stack.

.PHONY: redeploy redeploy-fast redeploy-frontend package-backend logs down help db-pull-from-fly

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

# Rebuild the runnable JAR that the backend Dockerfile COPYs from.
# Skips tests so this stays fast — run the test suite separately when
# you actually want to verify behaviour.
package-backend:  ## Rebuild backend JAR (skips tests).
	@echo "==> Building backend JAR (skipping tests)..."
	./mvnw -DskipTests -q package

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
