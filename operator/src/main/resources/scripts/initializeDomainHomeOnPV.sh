#!/bin/sh

# Copyright (c) 2023, Oracle and/or its affiliates.

scriptDir="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
if [ "${debug}" == "true" ]; then set -x; fi;

. ${scriptDir}/utils_base.sh
[ $? -ne 0 ] && echo "[SEVERE] Missing file ${scriptDir}/utils_base.sh" && exit 1
UNKNOWN_SHELL=true

checkEnv DOMAIN_HOME || exit 1

ORIGIFS=$IFS
trace "DOMAIN HOME is " $DOMAIN_HOME
trace "Running the script as "`id`

if [[ -f $DOMAIN_HOME ]]; then
  if [[ -f $DOMAIN_HOME/config/config.xml ]] ; then
    trace INFO "DOMAIN_HOME "$DOMAIN_HOME" already exists, no operation. Exiting with 0 return code"
    exit 0
  else
    trace SEVERE "DOMAIN_HOME "$DOMAIN_HOME" is not empty and does not contain any WebLogic Domain. Please specify an empty directory in n 'domain.spec.domainHome'."
    exit 1
  fi
fi

IFS='/'
# domain home is tokenized by '/' now
read -a dh_array <<< $DOMAIN_HOME
IFS=$OLDIFS

number_of_tokens=${#dh_array[@]}
trace "Number of tokens in domain home "$number_of_tokens

# walk through the list of tokens and reconstruct the path from beginning to find the
# base path
for (( i=1 ; i<=number_of_tokens; i++))
do
   temp_dir=${dh_array[@]:1:i}
   # parameter substitution turns spece to slash
   test_dir="/${temp_dir// //}"
   trace "Testing base mount path at "$test_dir
   if [ -d $test_dir ] ; then
       root_dir=test_dir
       trace "Found base mount path at "$test_dir
       break
   fi
done

if [ -z $root_dir ] ; then
   trace SEVERE "Error: Unable initialize domain home directory: 'domain.spec.domainHome' "$DOAMIN_HOME" is not under mountPath in any of the 'domain.spec.serverPod.volumeMounts'"
   exit 1
fi

SHARE_ROOT=$root_dir

trace "Creating domain home and setting the permission from share root "$SHARE_ROOT
if ! errmsg=$(mkdir -p $DOMAIN_HOME 2>&1)
then
  trace SEVERE "Could not create directory $DOMAIN_HOME specified in 'domain.spec.domainHome'.  Error: ${errmsg}"
  exit 1
fi

if ! errmsg=$(find $SHAER_ROOT ! -path "$SHARE_ROOT/.snapshot*" -exec chown 1000:0 {} \;)
then
  trace SEVERE "Failed to change directory permission at "$SHARE_ROOT" Error: "$errmsg
  exit 1
fi

trace "Creating domain home completed"
ls -Rl $DOMAIN_HOME
exit





