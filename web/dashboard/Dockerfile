# Static React SPA: no build-time secrets, calls the API from the browser rather than the
# container, so the same image works against any host that publishes the gateway on 8080.
#
# Two stages: build with Node, serve the compiled output with plain nginx. The final image
# contains no Node runtime at all — just static files and a web server.

FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

# Vite inlines env at build time, so the API origin has to be known here rather than at run time.
# Unset, src/api.ts falls back to localhost:8080 — correct for a laptop and for any host that
# publishes the gateway on the same origin the dashboard is served from, which is the common case
# described above. It is wrong wherever each port gets its own hostname: GitHub Codespaces and
# Cloud Run both do that, and there the dashboard would call a localhost:8080 that is the
# recruiter's own machine, not the gateway. Passing these turns that into a deployment decision
# instead of a silent 'nothing loads'. Still no secrets: these are public origins by definition,
# visible in any devtools network tab.
ARG VITE_API_BASE
ARG VITE_AUTH_BASE
ARG VITE_ENVIRONMENT_LABEL
ENV VITE_API_BASE=${VITE_API_BASE}
ENV VITE_AUTH_BASE=${VITE_AUTH_BASE}
ENV VITE_ENVIRONMENT_LABEL=${VITE_ENVIRONMENT_LABEL}

COPY . .
RUN npm run build

FROM nginx:1.31-alpine AS runtime

# Client-side routing: any path nginx doesn't recognise as a file falls back to index.html, so
# refreshing the browser on a route the SPA owns doesn't 404.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

# Runs unprivileged, same reasoning as the main Dockerfile. The image already ships a numeric
# "nginx" user (uid 101) for its worker processes; running the whole container as that user,
# rather than only the workers, is what lets Kubernetes' runAsNonRoot actually verify it — a
# named USER Kubernetes cannot resolve to a UID gets refused at the kubelet rather than started,
# which is how this was found. 8080, not 80: binding under 1024 needs a capability this container
# does not have, and a port kubelet can verify without one is simpler than granting it back.
#
# /var/cache/nginx and /run are root-owned in the base image — fine for the default "root starts,
# workers drop to nginx" split, wrong once the whole container runs as nginx from the start.
# Kubernetes gets working versions of both anyway (emptyDir volumes with fsGroup, see
# platform/k8s/30-services.yaml), but chowning them here too is what makes `docker run` and
# docker-compose work without needing volumes to paper over it — same image, same behavior,
# everywhere it runs.
RUN chown -R nginx:nginx /usr/share/nginx/html /etc/nginx/conf.d /var/cache/nginx /run
USER nginx

EXPOSE 8080
