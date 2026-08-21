docker run -d \
  --name mysql8 \
  --restart=always \
  -p 3306:3306 \
  -v mysql_data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD=root123123 \
  -e MYSQL_ROOT_HOST=% \
  -e MYSQL_DATABASE=rtdwh_mgmt \
  -e MYSQL_USER=db_user \
  -e MYSQL_PASSWORD=root123123 \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --default-authentication-plugin=mysql_native_password \
  --bind-address=0.0.0.0