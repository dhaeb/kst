#!/usr/bin/env groovy

import groovy.util.CliBuilder

println("Using literature search cmd tool version 1.0")


def chromium = "chromium"
def defaultBrowser = chromium
def browsersCliCommandMapping = [chromium : "chromium-browser",
			         "firefox" : "firefox --new-tab"]
			         
def cli = new CliBuilder(usage : 'search-for-literature')
cli.b(longOpt: 'browser', args : 1, argName : "browser", "use given browser to open search tabs: ${browsersCliCommandMapping.keySet()}, the default is '${defaultBrowser}'")
cli.h("shows this help message")
cli.p(longOpt:'phrase', args : 1, argName : "phrase", "The phrase to search for in the literature databases", required : true)

if(!(args.contains("-b") || args.contains("--browser"))){
  args = args as List
  args.addAll(["-b", defaultBrowser])
}

def options = cli.parse(args)
def showUsageAndExit = {
  cli.usage()
  System.exit(1)
}	

if(!options){
  System.exit(-1)
}	
if(options.h){
  showUsageAndExit()
}
def usedBrowser = options.b
println("using browser: ${usedBrowser}")
if(! browsersCliCommandMapping.containsKey(usedBrowser)){
  println("You need to specify a supported browser which is used for searching!")
  showUsageAndExit()
}

def usedPhrase = options.p

abstract class LiteratureDb {
  String base
  LiteratureDb(String base){
    this.base = base
  }
  abstract String convertSearchPhrase(String phrase)
  
  def toString(String phrase){
    return String.format(base, convertSearchPhrase(phrase))
  }
}

class ConcatLiteratureDb extends LiteratureDb {
  def concatinator
  ConcatLiteratureDb(String base, String concatinator){
    super(base)
    this.concatinator = concatinator
  }
  String convertSearchPhrase(String p) {
      def phrases = p.split() 
      if(phrases.size() == 1){
        p
      } else {
      	phrases.join(concatinator)	      
      }
    }
}

def urlPatternList = [
  new LiteratureDb("http://arxiv.org/find/all/1/all:%s/0/1/0/all/0/1") {
    String convertSearchPhrase(String p) {
      def phrases = p.split() 
      def concatinator = "+AND+"
      if(phrases.size() == 1){
        ${phrases.head()}
      } else {
      	concatinator + phrases.tail().join(concatinator) + "+${phrases.head()}"
      }
    }
  },
  new ConcatLiteratureDb("https://scholar.google.de/scholar?hl=de&q=%s&btnG=&lr=", "+"),
  new ConcatLiteratureDb("http://link.springer.com/search?query=%s&showAll=true", "+"),
  new ConcatLiteratureDb("http://ieeexplore.ieee.org/search/searchresult.jsp?queryText=%s&newsearch=true", "%20")
]

def browserCommand  = browsersCliCommandMapping.get(usedBrowser)
urlPatternList.each {literatureDb -> 
  def curUrl = literatureDb.toString(usedPhrase)
  println("opening tab with url: ${curUrl}...")
  "${browserCommand} ${curUrl}".execute()
}
/**"+AND+requirements+AND+things+AND+internet+of"

http://arxiv.org/find/all/1/all:+AND+llllll+AND+bdf+AND+asdf+asdf/0/1/0/all/0/1
asdf asdf bdf llllll*/




