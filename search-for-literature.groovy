#!/usr/bin/env groovy

import groovy.util.CliBuilder
@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1' )
@GrabExclude('commons-beanutils:commons-beanutils')
import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.ContentType.URLENC

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
  protected String base
  LiteratureDb(String base){
    this.base = base
  }
  abstract String convertSearchPhrase(String phrase)
  
  def toString(String phrase){
    return String.format(getBaseURI(), convertSearchPhrase(phrase))
  }
  
  def getBaseURI(){
    return base
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

class PlusConcatedLiteratureDb extends ConcatLiteratureDb {
  PlusConcatedLiteratureDb(String base){
    super(base, "+")
  }
}

class WebOfScience extends ConcatLiteratureDb {

  WebOfScience(){
    super("%s", ' ')
  }
  
  @Override
  String convertSearchPhrase(String p){
    def plusConcatedSearchPhrase = super.convertSearchPhrase(p)
    def sidAddressClient = aquireSession()
    def sid = extractSid(sidAddressClient)
    performWebOfSienceSearch(sidAddressClient, plusConcatedSearchPhrase, sid)
  }
  
  def aquireSession(){
      def client = new HTTPBuilder('http://www.webofknowledge.com' )
      client.get(path : '/', query : ['Error' : 'Client.NullSessionID']){resp ->  
	assert resp.statusLine.statusCode == 200
      }
      return client
  }
  def extractSid(httpBuilder){
      def cookieList = httpBuilder.getClient().getCookieStore().getCookies()
      cookieList.findAll{cookie -> cookie.name == 'SID'}.first().getValue()
  }

  def performWebOfSienceSearch(httpClient, String searchTerm, String sid){
      def postBody = [fieldCount:"1",
		  action:"search",
		  product:"UA",
		  search_mode:"GeneralSearch",
		  SID: sid,
		  max_field_count:"25",
		  max_field_notice:"Notice:+You+cannot+add+another+field.",
		  input_invalid_notice:"Search+Error:+Please+enter+a+search+term.",
		  exp_notice:"Search+Error:+Patent+search+term+could+be+found+in+more+than+one+family+(unique+patent+number+required+for+Expand+option)+",
		  input_invalid_notice_limits:"+<br/>Note:+Fields+displayed+in+scrolling+boxes+must+be+combined+with+at+least+one+other+search+field.",
		  sa_params:"UA||$sid|http://apps.webofknowledge.com|'",
		  formUpdated:"true",
		  "value(input1)": searchTerm,
		  "value(select1)":"TS",
		  x:"13",
		  y:"23",
		  "value(hidInput1)":"",
		  limitStatus:"collapsed",
		  ss_lemmatization:"On",
		  ss_spellchecking:"Suggest",
		  period:"Range Selection",
		  range:"ALL",
		  startYear:"1945",
		  endYear:"2016",
		  update_back2search_link_param:"yes",
		  ssStatus:"display:none",
		  ss_showsuggestions:"ON",
		  ss_query_language:"auto",
		  ss_numDefaultGeneralSearchFields:"1",
		  rs_sort_by:"PY.D;LD.D;SO.A;VL.D;PG.A;AU.A"
		  ] // will be url-encoded
	httpClient.setUri('http://apps.webofknowledge.com')
	httpClient.post( path: '/UA_GeneralSearch.do', body: postBody,
	requestContentType: URLENC ) { resp ->
	  assert resp.statusLine.statusCode == 302
	  return resp.getHeaders().Location
	}
    }
}

def urlPatternList = [
  new LiteratureDb("http://arxiv.org/find/all/1/all:%s/0/1/0/all/0/1") {
    String convertSearchPhrase(String p) {
      def phrases = p.split() 
      def concatinator = "+AND+"
      if(phrases.size() == 1){
        phrases.head()
      } else {
      	concatinator + phrases.tail().join(concatinator) + "+${phrases.head()}"
      }
    }
  },
  new PlusConcatedLiteratureDb("https://scholar.google.de/scholar?hl=de&q=%s&btnG=&lr="),
  new PlusConcatedLiteratureDb("http://link.springer.com/search?query=%s&showAll=true"),
  new PlusConcatedLiteratureDb("http://dl.acm.org/results.cfm?query=%s"),
  new ConcatLiteratureDb("http://ieeexplore.ieee.org/search/searchresult.jsp?queryText=%s&newsearch=true", "%20"),
  new PlusConcatedLiteratureDb("http://www.sciencedirect.com/science?_ob=QuickSearchURL&_method=submitForm&_acct=C000052823&searchtype=a&_origin=home&_zone=qSearch&md5=e33a5e4c240c76c7e65ce1d150c72358&qs_all=%s&qs_author=&qs_title=&qs_vol=&qs_issue=&qs_pages=&sdSearch="),
  new WebOfScience()
]

def browserCommand  = browsersCliCommandMapping.get(usedBrowser)
urlPatternList.each {literatureDb -> 
  def curUrl = literatureDb.toString(usedPhrase)
  println("opening tab with url: ${curUrl}...")
  "${browserCommand} ${curUrl}".execute()
}