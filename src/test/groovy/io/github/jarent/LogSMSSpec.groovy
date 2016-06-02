package io.github.jarent

import com.sforce.soap.partner.fault.UnexpectedErrorFault
import groovy.json.JsonSlurper
import groovy.text.Template

import org.junit.Test;
import org.mule.api.MuleMessage;
import org.mule.api.client.OperationOptions
import org.mule.munit.runner.functional.FunctionalMunitSuite
import org.mule.module.http.api.client.HttpRequestOptionsBuilder
import org.mule.modules.salesforce.exception.SalesforceException
import io.github.jarent.munit.MunitSpecification
import com.sforce.soap.partner.fault.ExceptionCode

import spock.lang.*

@Title("Log SMS API Specification")
class LogSMSSpec extends MunitSpecification {

	@Override
	protected String getConfigResources() {
		
			return ["log-sms.xml"
					].join(",")
	}
	
	@Override
	protected boolean haveToDisableInboundEndpoints() {
		return false;
	}

	@Override
	protected boolean haveToMockMuleConnectors() {
		return false;
	}
	
	private static final String INVALID_REQUEST = '{"phone":"6309999999"}'
	
	private static final String VALID_REQUEST = '''
			 {"LogSMSRequest" :	
				{
				"occuredAt": "October 12, 2015 at 08:05PM", 
				"whoId": "00000", 
				"text":"Hello World!"
				}
		     }
			'''	

@Unroll
def "should #scenario"() {
		
	expect: "'#responseHttpStatus' http.status response code and #responseMessage as a result"
	
	MuleMessage result = send request
	result.getInboundProperty('http.status') == responseHttpStatus
	result.getPayloadAsString() == responseMessage
	
	where: "request is #request"
	
			scenario 			|	 request      | responseHttpStatus | responseMessage
	'Reject Invalid Request'    | INVALID_REQUEST |    400			  | '{ "exception" : {"code": "INVALID_REQUEST", "message": "Bad request" }}'
	'Fail When Invalid WhoId'   | VALID_REQUEST   |    500			  | '{ "message": "Failed because of Name ID: id value of incorrect type: 00000" }'	
}
	
	
	def "should Fail When Salesforce Fault"() {
		
		given: "Exception thrown from Saleforce create call"
		UnexpectedErrorFault error = new UnexpectedErrorFault()
		error.setExceptionMessage('Some except')
		error.setExceptionCode(ExceptionCode.INVALID_OPERATION_WITH_EXPIRED_PASSWORD)
		
		whenMessageProcessor("create").ofNamespace("sfdc").
		withAttributes(['doc:name': 'Save Task in Salesforce']).thenThrow(new SalesforceException(error))
		
		when: "Valid request is sent"
		MuleMessage result = send '''
			 {"LogSMSRequest" :	
				{
				"occuredAt": "October 12, 2015 at 08:05PM", 
				"whoId": "doesn't matter", 
				"text":"Hello World!"
				}
		     }
			'''		
		
		then: "Expect 500 Internal Server Error status code"
		assert result.getInboundProperty('http.status') == 500
		
		def json = new JsonSlurper().parseText(result.getPayloadAsString())
		
		and: "Salesforce exception code"
		json.exception.code == error.getExceptionCode().toString()
		
		and: "Salesforce exception message returned in the response"
		json.exception.message == error.getExceptionMessage()
		
	}
	
	def "should Success For Valid Request"() {
		
		given: "'newTaskId' returned from SalesForce create call"
		
		def newTaskId = 'newTaskId'
		whenMessageProcessor("create").ofNamespace("sfdc").
		withAttributes(['doc:name': 'Save Task in Salesforce']).thenReturn(muleMessageWithPayload(
					//inline map
					[[id:newTaskId,
					  errors:[]
					]]
					))
		
		and: "Valid request"
		def requestBuilder = new groovy.json.JsonBuilder()
		requestBuilder.LogSMSRequest {
				occuredAt "October 12, 2015 at 08:05PM"
				whoId "validId"
				text "Hello World!"
			}
		
									
		when: "Request is sent"
		MuleMessage result = send requestBuilder.toString()
		
		then: "Expect 200 http response status code"
		result.getInboundProperty('http.status') == 200
		
		and: "'newTaskId' returned in the response"
		result.getPayloadAsString() =~ newTaskId
	}
	
	
	
	
	
	
	private MuleMessage send(String requestBody) {
		MuleMessage request = testEvent(requestBody).getMessage().with {
			it.setOutboundProperty('Content-Type','application/json')
			return it
		}
		
		OperationOptions options = HttpRequestOptionsBuilder.newOptions()
		.method('post')
		.disableStatusCodeValidation()
		.responseTimeout(10000)
		.build()
				
		
		return muleContext.getClient().send('http://localhost:8081/api/salesforce/log/sms', request, options)
	}
	
}  
