# Production image for the React app.
#
# Two stages, same reasoning as the backend: Node builds, nginx serves. The
# finished artefact is static files — there is no Node process in production
# at all, which is both faster and a much smaller attack surface.

# ---------- stage 1: build ----------
FROM node:22-alpine AS build
WORKDIR /app

# package files first so the (slow) npm install layer is cached independently
# of source changes.
COPY package*.json ./
RUN npm ci

COPY . .

# Vite inlines VITE_* variables at BUILD time — they are baked into the
# JavaScript, not read at runtime. That means the API URL must be supplied
# HERE, as a build argument, and a different backend means a rebuild.
# It also means: never put a secret in one. Everything here ships to the
# browser in plain text.
ARG VITE_API_URL
ARG VITE_GOOGLE_CLIENT_ID
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID

RUN npm run build

# ---------- stage 2: serve ----------
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
