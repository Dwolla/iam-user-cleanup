package com.dwolla.lambda.iam

import com.dwolla.lambda.cloudformation.CloudFormationRequestType.{CreateRequest, DeleteRequest, UpdateRequest}
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.iam.IamUserCleanupLambda.RequestValidation
import io.circe.JsonObject
import io.circe.literal._
import model._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RequestValidationSpec extends AnyFlatSpec with Matchers with EitherValues {
  private val responseUrl = "response-url"
  private val stackId = "stack-id".asInstanceOf[StackId]
  private val requestId = "request-id".asInstanceOf[RequestId]
  private val resourceType = "resource-type".asInstanceOf[ResourceType]
  private val logicalResourceId = "logical-resource-id".asInstanceOf[LogicalResourceId]
  private val resourceProperties = json"""{
                                    "userArn": "user-arn",
                                    "username": "user"
                                  }""".asObject
  private val physicalResourceId = "user-arn".asInstanceOf[PhysicalResourceId]
  private val username = "user"
  private val emptyResourceProperties = Option(JsonObject.empty)

  behavior of "Create requests"

  it should "be valid when no physical resource id is specified but User ARN is in Resource Properties" in {
    val req = CloudFormationCustomResourceRequest(CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, resourceProperties, None)

    new RequestValidation(req).validated should be(Right(RequestParameters(NoOp, physicalResourceId, username)))
  }

  it should "be invalid when a physical resource id is specified or missing resource properties" in {
    val req = CloudFormationCustomResourceRequest(CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option("physicalResourceId").map(_.asInstanceOf[PhysicalResourceId]), None, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(CreateRequestWithPhysicalResourceId)
    validated.left.value.toList should contain(MissingResourcePropertiesValidationError)
  }

  it should "be invalid when a username is missing" in {
    val req = CloudFormationCustomResourceRequest(CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(MissingResourceProperty("username"))
    validated.left.value.toList should contain(MissingResourceProperty("userArn"))
  }

  behavior of "Update requests"

  it should "be valid when a physical resource ID and required properties are present" in {
    val req = CloudFormationCustomResourceRequest(UpdateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), resourceProperties, None)

    new RequestValidation(req).validated should be(Right(RequestParameters(NoOp, physicalResourceId, username)))
  }

  it should "be invalid when a physical resource ID and required properties are missing" in {
    val req = CloudFormationCustomResourceRequest(UpdateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(MissingPhysicalResourceId)
    validated.left.value.toList should contain(MissingResourceProperty("username"))
    validated.left.value.toList should contain(MissingResourceProperty("userArn"))
  }

  it should "be invalid when resource properties is missing" in {
    val req = CloudFormationCustomResourceRequest(UpdateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), None, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(MissingResourcePropertiesValidationError)
  }

  behavior of "Delete requests"

  it should "be valid when a physical resource ID and required properties are present" in {
    val req = CloudFormationCustomResourceRequest(DeleteRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), resourceProperties, None)

    new RequestValidation(req).validated should be(Right(RequestParameters(Delete, physicalResourceId, username)))
  }

  it should "be invalid when a physical resource ID and required properties are missing" in {
    val req = CloudFormationCustomResourceRequest(DeleteRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(MissingPhysicalResourceId)
    validated.left.value.toList should contain(MissingResourceProperty("username"))
    validated.left.value.toList should contain(MissingResourceProperty("userArn"))
  }

  it should "be invalid when resource properties is missing" in {
    val req = CloudFormationCustomResourceRequest(DeleteRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), None, None)

    val validated = new RequestValidation(req).validated
    validated.left.value.toList should contain(MissingResourcePropertiesValidationError)
  }

}
