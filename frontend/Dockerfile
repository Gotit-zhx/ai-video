FROM nginx:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY downloads /usr/share/nginx/html/plugin-downloads
COPY index.html /usr/share/nginx/html/index.html
COPY resend-theme.css /usr/share/nginx/html/resend-theme.css
COPY image-progress.css /usr/share/nginx/html/image-progress.css
COPY app.js /usr/share/nginx/html/app.js
COPY video-script-terminal.js video-script-terminal.css /usr/share/nginx/html/
COPY product-bulk-upload.js product-bulk-size-chart.js /usr/share/nginx/html/
COPY product-bulk-history.js product-bulk-history.css /usr/share/nginx/html/
COPY product-main-image-styles.js product-main-image-styles.css /usr/share/nginx/html/
COPY commerce-planner.js commerce-planner.css /usr/share/nginx/html/
COPY commerce-batches.js /usr/share/nginx/html/
COPY commerce-ad-evaluation.js /usr/share/nginx/html/
COPY commerce-designs.js /usr/share/nginx/html/
COPY commerce-studio.js commerce-studio.css /usr/share/nginx/html/
COPY commerce-motion.js commerce-motion.css /usr/share/nginx/html/
COPY commerce-reference-layout.css /usr/share/nginx/html/
COPY commerce-workbench.js /usr/share/nginx/html/
COPY commerce-creative-plan.js /usr/share/nginx/html/
COPY commerce-batch-artifacts.js /usr/share/nginx/html/
COPY commerce-material-slots.js /usr/share/nginx/html/
COPY commerce-print-editor.js commerce-print-surface.js /usr/share/nginx/html/
COPY shop-settings.js /usr/share/nginx/html/
COPY logo.svg /usr/share/nginx/html/logo.svg
COPY assets /usr/share/nginx/html/assets
COPY vendor /usr/share/nginx/html/vendor

EXPOSE 80 443

COPY commerce-template-studio.css /usr/share/nginx/html/

COPY commerce-artwork-library.css commerce-artwork-library.js commerce-publication.js /usr/share/nginx/html/

COPY commerce-motion-demo.html commerce-motion-demo.js commerce-motion-demo.css /usr/share/nginx/html/

COPY i18n.js i18n.css /usr/share/nginx/html/
COPY i18n/catalog.js i18n/catalog.json i18n/slots.json /usr/share/nginx/html/i18n/

COPY knowledge-workspace.js knowledge-workspace.css /usr/share/nginx/html/
COPY hr-workspace.js hr-workspace.css hr-pool.js hr-demands.js hr-departments.js hr-passive.js /usr/share/nginx/html/
COPY hr-bi.js hr-desktop-bridge.js hr-onboarding.js hr-resume-preview.js hr-sourcing.js /usr/share/nginx/html/
COPY product-listing-templates.js product-listing-templates.css /usr/share/nginx/html/
COPY styles.css mobile.css /usr/share/nginx/html/
COPY 50x.html /usr/share/nginx/html/
