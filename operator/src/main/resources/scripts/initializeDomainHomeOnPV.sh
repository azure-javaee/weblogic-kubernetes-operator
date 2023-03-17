#!/bin/sh
# Copyright (c) 2023, Oracle and/or its affiliates.

scriptDir="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
if [ "${debug}" == "true" ]; then set -x; fi;

. ${scriptDir}/utils_base.sh
[ $? -ne 0 ] && echo "[SEVERE] Missing file ${scriptDir}/utils_base.sh" && exit 1
UNKNOWN_SHELL=true

checkEnv DOMAIN_HOME AUXILIARY_IMAGE_TARGET_PATH || exit 1

mkdir -m 750 -p "${AUXILIARY_IMAGE_TARGET_PATH}/auxiliaryImageLogs/" || exit 1
chown -R 1000:0 "${AUXILIARY_IMAGE_TARGET_PATH}"
output_file="${AUXILIARY_IMAGE_TARGET_PATH}/auxiliaryImageLogs/initializeDomainHomeOnPV.out"

failure_exit() {
  exit 1
}

create_success_file_and_exit() {
  echo "0" > "${AUXILIARY_IMAGE_TARGET_PATH}/auxiliaryImageLogs/initializeDomainHomeOnPV.suc"
  chown 1000:0 "${AUXILIARY_IMAGE_TARGET_PATH}/auxiliaryImageLogs/initializeDomainHomeOnPV.suc"
  exit
}


ORIGIFS=$IFS
trace "DOMAIN HOME is " $DOMAIN_HOME >> "$output_file"
trace "Running the script as "`id` >> "$output_file"

if [ -f "$DOMAIN_HOME" ]; then
  if [ -f "$DOMAIN_HOME/config/config.xml" ] ; then
    trace INFO "DOMAIN_HOME "$DOMAIN_HOME" already exists, no operation. Exiting with 0 return code" >> "$output_file"
    create_success_file_and_exit
  else
    trace SEVERE "DOMAIN_HOME "$DOMAIN_HOME" is not empty and does not contain any WebLogic Domain. Please specify an empty directory in n 'domain.spec.domainHome'." >> "$output_file"
    failure_exit
  fi
fi

IFS='/'
# domain home is tokenized by '/' now
read -a dh_array <<< $DOMAIN_HOME
IFS=$OLDIFS

number_of_tokens=${#dh_array[@]}
trace "Number of tokens in domain home "$number_of_tokens  >> "$output_file"

# walk through the list of tokens and reconstruct the path from beginning to find the
# base path
for (( i=1 ; i<=number_of_tokens; i++))
do
   temp_dir=${dh_array[@]:1:i}
   # parameter substitution turns spece to slash
   test_dir="/${temp_dir// //}"
   trace "Testing base mount path at "$test_dir >> "$output_file"
   if [ -d $test_dir ] ; then
       trace "Found base mount path at "$test_dir >> "$output_file"
       SHARE_ROOT=$test_dir

       trace "Creating domain home and setting the permission from share root "$SHARE_ROOT >> "$output_file"
       if ! errmsg=$(mkdir -p "$DOMAIN_HOME" 2>&1)
         then
           trace SEVERE "Could not create directory "${DOMAIN_HOME}" specified in 'domain.spec.domainHome'.  Error: "${errmsg} >> "$output_file"
           failure_exit
       fi

       if ! errmsg=$(find "$SHARE_ROOT" ! -path "$SHARE_ROOT/.snapshot*" -exec chown 1000:0 {} \;)
         then
           trace SEVERE "Failed to change directory permission at "$SHARE_ROOT" Error: "$errmsg >> "$output_file"
           failure_exit
       fi

       trace "Creating domain home completed"
       chown 1000:0 $output_file
       create_success_file_and_exit
   fi
done

trace SEVERE "Error: Unable initialize domain home directory: 'domain.spec.domainHome' "$DOMAIN_HOME" is not under mountPath in any of the 'domain.spec.serverPod.volumeMounts'" >> "$output_file"
failure_exit

