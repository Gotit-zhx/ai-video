FROM nginx:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY app.js /usr/share/nginx/html/app.js
COPY logo.svg /usr/share/nginx/html/logo.svg
COPY assets /usr/share/nginx/html/assets
COPY vendor /usr/share/nginx/html/vendor

EXPOSE 80
