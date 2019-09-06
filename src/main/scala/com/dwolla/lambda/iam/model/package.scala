package com.dwolla.lambda.iam.model

import cats._
import cats.data._
import cats.implicits._
import com.dwolla.lambda.cloudformation._
import io.circe._

object RequestValidationException {
  private implicit val showRequestValidationError: Show[RequestValidationError] = Show.fromToString[RequestValidationError]
  def nelToString(issues: NonEmptyList[RequestValidationError]): String = issues.mkString_(" - ", "\n - ", "")
}

case class RequestValidationException(issues: NonEmptyList[RequestValidationError]) extends RuntimeException(
  s"""Incoming request failed validation due to the following issues:
     |
     |${RequestValidationException.nelToString(issues)}""".stripMargin)
sealed trait RequestValidationError
case object CreateRequestWithPhysicalResourceId extends RequestValidationError
case object MissingPhysicalResourceId extends RequestValidationError
case object MissingResourcePropertiesValidationError extends RequestValidationError
case class MissingResourceProperty(propertyName: String) extends RequestValidationError
case class InvalidUsername(json: Json, cause: Exception) extends Exception(cause) with RequestValidationError
case class RequestParameters(action: Action, physicalResourceId: PhysicalResourceId, username: String)

sealed trait Action
case object NoOp extends Action
case object Delete extends Action
