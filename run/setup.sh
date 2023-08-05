#!/bin/bash

# This script will perform a first-time setup of the run/ directory, as well as 
# download third party language models and other files that aren't suitable for
# git

set -e

function download_model {
  model=$1
  url=$2

  if [ ! -f $model ]; then
    echo "** Downloading $url"
    wget -O $model $url
  fi
}

pushd $(dirname $0)

mkdir -p model logs db samples install vol/ir/{0,1}/ vol/{lr,lw} vol/iw/{0,1}/search-sets vol/{tmpf,tmps} data samples/export

download_model model/English.DICT https://raw.githubusercontent.com/datquocnguyen/RDRPOSTagger/master/Models/POS/English.DICT
download_model model/English.RDR https://raw.githubusercontent.com/datquocnguyen/RDRPOSTagger/master/Models/POS/English.RDR
download_model model/opennlp-sentence.bin https://mirrors.estointernet.in/apache/opennlp/models/ud-models-1.0/opennlp-en-ud-ewt-sentence-1.0-1.9.3.bin
download_model model/opennlp-tokens.bin https://mirrors.estointernet.in/apache/opennlp/models/ud-models-1.0/opennlp-en-ud-ewt-tokens-1.0-1.9.3.bin
download_model model/ngrams.bin https://downloads.marginalia.nu/model/ngrams.bin
download_model model/tfreq-new-algo3.bin https://downloads.marginalia.nu/model/tfreq-new-algo3.bin

download_model data/IP2LOCATION-LITE-DB1.CSV.ZIP https://download.ip2location.com/lite/IP2LOCATION-LITE-DB1.CSV.ZIP
unzip -qn -d data data/IP2LOCATION-LITE-DB1.CSV.ZIP

download_model data/adblock.txt https://downloads.marginalia.nu/data/adblock.txt
if [ ! -f data/suggestions.txt ]; then
  download_model data/suggestions.txt.gz https://downloads.marginalia.nu/data/suggestions.txt.gz
  gunzip data/suggestions.txt.gz
fi

if [ ! -d conf ]; then
  cp -r template/conf .
fi

popd
