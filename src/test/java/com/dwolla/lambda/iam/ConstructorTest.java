package com.dwolla.lambda.iam;

import cats.effect.IO;
import com.dwolla.lambda.cloudformation.CloudFormationCustomResourceRequest;
import com.dwolla.lambda.cloudformation.CloudFormationRequestType;
import com.dwolla.lambda.cloudformation.HandlerResponse;
import scala.Option;

public class ConstructorTest {
    public static void main(String[] args) {
        final Handler h = new Handler(); // no argument constructor is required for Lambda to work!

        final IO<HandlerResponse> io = h.handleRequest(CloudFormationCustomResourceRequest.apply(CloudFormationRequestType.CreateRequest$.MODULE$, "", "", "", "", "", Option.apply(null), Option.apply(null), Option.apply(null)));

        final HandlerResponse handlerResponse = io.unsafeRunSync();

        System.out.println(handlerResponse);
    }
}
