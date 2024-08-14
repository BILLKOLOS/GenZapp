#!/bin/bash

# Directory to search
DIR="/data/data/com.termux/files/home/GenZapp/app"

# Find and replace all instances of 'GenZapp' with 'GenZapp'
find $DIR -type f -exec sed -i 's/GenZapp/GenZapp/g' {} +
find $DIR -type f -exec sed -i 's/GenZapp/GenZapp/g' {} +
find $DIR -type f -exec sed -i 's/GenZapp/GenZapp/g' {} +
find $DIR -type f -exec sed -i 's/[Ss][Ii][Gg][Nn][Aa][Ll]/GenZapp/g' {} +

echo "Replacement complete."

