package com.dwolla.lambda.iam;

public class ConstructorTest {
    public static void main(String[] args) {
        final Handler h = new Handler(); // no argument constructor is required for Lambda to work!
    }
}
