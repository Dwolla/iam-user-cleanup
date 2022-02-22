package com.dwolla.lambda.iam

import com.dwolla.lambda.iam.IamUserCleanupLambda.RequestValidation
import com.dwolla.lambda.iam.model._
import com.eed3si9n.expecty.Expecty.expect
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import io.circe.JsonObject
import io.circe.literal._
import io.circe.syntax._
import munit.FunSuite
import org.http4s.syntax.all._

class RequestValidationSpec extends FunSuite {
  private val responseUrl = uri"https://response-url"
  private val stackId = StackId("stack-id")
  private val requestId = RequestId("request-id")
  private val resourceType = ResourceType("resource-type")
  private val logicalResourceId = LogicalResourceId("logical-resource-id")
  private val resourceProperties = JsonObject("userArn" -> "user-arn".asJson, "username" -> "user".asJson)
  private val physicalResourceId = PhysicalResourceId.unsafeApply("user-arn")
  private val username = Username("user")
  private val emptyResourceProperties = JsonObject.empty

  test("Create requests should be valid when no physical resource id is specified but User ARN is in Resource Properties") {
    val req = CloudFormationCustomResourceRequest[JsonObject](CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, resourceProperties, None)

    assertEquals(new RequestValidation(req).validated, Right(RequestParameters(NoOp, physicalResourceId, username)))
  }

  test("Create requests should be invalid when a physical resource id is specified or missing resource properties") {
    val req = CloudFormationCustomResourceRequest(CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, PhysicalResourceId("physicalResourceId"), emptyResourceProperties, None)

    expect(new RequestValidation(req).validated.swap.exists(_.toList.contains(CreateRequestWithPhysicalResourceId)))
  }

  test("Create requests should be invalid when a username is missing") {
    val req = CloudFormationCustomResourceRequest(CreateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("username"))))
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("userArn"))))
  }

  test("Update requests should be valid when a physical resource ID and required properties are present") {
    val req = CloudFormationCustomResourceRequest(UpdateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), resourceProperties, None)

    assertEquals(new RequestValidation(req).validated, Right(RequestParameters(NoOp, physicalResourceId, username)))
  }

  test("Update requests should be invalid when a physical resource ID and required properties are missing") {
    val req = CloudFormationCustomResourceRequest(UpdateRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    expect(validated.swap.exists(_.toList.contains(MissingPhysicalResourceId)))
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("username"))))
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("userArn"))))
  }

  test("Delete requests should be valid when a physical resource ID and required properties are present") {
    val req = CloudFormationCustomResourceRequest(DeleteRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, Option(physicalResourceId), resourceProperties, None)

    assertEquals(new RequestValidation(req).validated, Right(RequestParameters(Delete, physicalResourceId, username)))
  }

  test("Delete requests should be invalid when a physical resource ID and required properties are missing") {
    val req = CloudFormationCustomResourceRequest(DeleteRequest, responseUrl, stackId, requestId, resourceType, logicalResourceId, None, emptyResourceProperties, None)

    val validated = new RequestValidation(req).validated
    expect(validated.swap.exists(_.toList.contains(MissingPhysicalResourceId)))
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("username"))))
    expect(validated.swap.exists(_.toList.contains(MissingResourceProperty("userArn"))))
  }
}
