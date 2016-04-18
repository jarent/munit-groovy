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
import com.sforce.soap.partner.fault.ExceptionCode

class LogSMSTest extends FunctionalMunitSuite   {
	
	
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
	
	@Test
	public void shouldRejectInvalidRequest() {
		
		//when											
		MuleMessage result = send '{"phone":"6309999999"}'
		
		//then
		assert result.getInboundProperty('http.status') == 400
		assert result.getPayloadAsString() == '{ "exception" : {"code": "INVALID_REQUEST", "message": "Bad request" }}'		
	}
	
	@Test
	public void shouldFailWithInvalidWhoId() {
		
		//given
		//template and multiline strings
		
		Template requestTemplate = new groovy.text.SimpleTemplateEngine().createTemplate('''
			 {"LogSMSRequest" :	
				{
				"occuredAt": "October 12, 2015 at 08:05PM", 
				"whoId": "${whoId}", 
				"text":"Hello World!"
				}
		     }
			''')
		
		//when
		MuleMessage result = send requestTemplate.make([whoId: "00000"]).toString() 
		
		//then
		assert result.getInboundProperty('http.status') == 500
		assert result.getPayloadAsString() == '{ "message": "Failed because of Name ID: id value of incorrect type: 00000" }'
	}
	
	@Test
	public void shouldFailWhenSalesforceFault() {
		
		//given		
		UnexpectedErrorFault error = new UnexpectedErrorFault()			
		error.setExceptionMessage('Some except')
		error.setExceptionCode(ExceptionCode.INVALID_OPERATION_WITH_EXPIRED_PASSWORD)							
		
		//when
		
		whenMessageProcessor("create").ofNamespace("sfdc").
		withAttributes(['doc:name': 'Create Task']).thenThrow(new SalesforceException(error))
		
		MuleMessage result = send '''
			 {"LogSMSRequest" :	
				{
				"occuredAt": "October 12, 2015 at 08:05PM", 
				"whoId": "doesn't matter", 
				"text":"Hello World!"
				}
		     }
			'''		
		
		//then			
		assert result.getInboundProperty('http.status') == 500
		
		def json = new JsonSlurper().parseText(result.getPayloadAsString())
		
		json.exception.code == error.getExceptionCode().toString()
		json.exception.message == error.getExceptionMessage()	
		
	}
	
	
	@Test
	public void shouldSuccessForValidRequest() {

		//given
		def newTaskId = 'newTaskId'
		
		//JSON Builder sample
		def requestBuilder = new groovy.json.JsonBuilder()		
		requestBuilder.LogSMSRequest {
				occuredAt "October 12, 2015 at 08:05PM"
				whoId "validId"
				text "Hello World!"
			}						
		//when
		
		whenMessageProcessor("create").ofNamespace("sfdc").
		withAttributes(['doc:name': 'Create Task']).thenReturn(muleMessageWithPayload(
					//inline map
					[[id:newTaskId, 
					  errors:[]
					]]
					))
									
				
		MuleMessage result = send requestBuilder.toString()
		
		//then
		assert result.getInboundProperty('http.status') == 200
		assert result.getPayloadAsString() =~ newTaskId
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
