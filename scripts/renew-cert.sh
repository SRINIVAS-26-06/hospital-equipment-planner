#!/bin/bash
set -e

cd /home/ubuntu/hospital-equipment-planner

echo "Checking SSL certificate renewal..."

docker run --rm \
  -v "$PWD/certbot/conf:/etc/letsencrypt" \
  -v "$PWD/certbot/www:/var/www/certbot" \
  certbot/certbot renew --quiet

echo "Reloading Nginx..."

docker exec hospital-equipment-planner-nginx-1 nginx -s reload

echo "SSL renewal check completed."
