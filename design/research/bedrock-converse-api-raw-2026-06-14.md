===== PAGE 0 (47595 chars) =====
# Inference using Converse API
The Converse API is available on the `bedrock-runtime` endpoint only.
You can use the Amazon Bedrock Converse API to create conversational applications that send and receive messages to and from an Amazon Bedrock model. For example, you can create a chat bot that maintains a conversation over many turns and uses a persona or tone customization that is unique to your needs, such as a helpful technical support assistant.
To use the Converse API, you use the [Converse][1] or [ConverseStream][2] (for streaming responses) operations to send messages to a model. It is possible to use the existing base inference operations ([InvokeModel][3] or [InvokeModelWithResponseStream][4]) for conversation applications. However, we recommend using the Converse API as it provides consistent API, that works with all Amazon Bedrock models that support messages. This means you can write code once and use it with different models. Should a model have unique inference parameters, the Converse API also allows you to pass those unique parameters in a model specific structure.
You can use the Converse API to implement [tool use][5] and [guardrails][6] in your applications.
###### Note
* With Mistral AI and Meta models, the Converse API embeds your input in a model-specific prompt template that enables conversations.
* Restrictions apply to the following operations: `InvokeModel`, `InvokeModelWithResponseStream`, `Converse`, and `ConverseStream`. See [API restrictions][7] for details.
For code examples, see the following:
* Python examples for this topic – [Converse API examples][8]
* Various languages and models – [Code examples for Amazon Bedrock Runtime using AWS SDKs][9]
* Java tutorial – [A Java developer's guide to Bedrock's new Converse API][10]
* JavaScript tutorial – [A developer's guide to Bedrock's new Converse API][11]
## Using the Converse API
To use the Converse API, you call the `Converse` or `ConverseStream` operations to send messages to a model. To call `Converse`, you require permission for the `bedrock:InvokeModel` operation. To call `ConverseStream`, you require permission for the `bedrock:InvokeModelWithResponseStream` operation.
### Request
When you make a [Converse][1] request with an [Amazon Bedrock runtime endpoint][12], you can include the following fields:
* **modelId** – A required parameter in the header that lets you specify the resource to use for inference.
* The following fields let you customize the prompt:
 * **messages** – Use to specify the content and role of the prompts.
 * **system** – Use to specify system prompts, which define instructions or context for the model.
 * **inferenceConfig** – Use to specify inference parameters that are common to all models. Inference parameters influence the generation of the response.
 * **additionalModelRequestFields** – Use to specify inference parameters that are specific to the model that you run inference with.
 * **promptVariables** – (If you use a prompt from Prompt management) Use this field to define the variables in the prompt to fill in and the values with which to fill them.
* The following fields let you customize how the response is returned:
 * **guardrailConfig** – Use this field to include a guardrail to apply to the entire prompt.
 * **toolConfig** – Use this field to include a tool to help a model generate responses.
 * **additionalModelResponseFieldPaths** – Use this field to specify fields to return as a JSON pointer object.
 * **serviceTier** – Use this field to specify the service tier for a particular request
* **requestMetadata** – Use this field to include metadata that can be filtered on when using invocation logs.
###### Note
The following restrictions apply when you use a Prompt management prompt with `Converse` or `ConverseStream`:
* You can't include the `additionalModelRequestFields`, `inferenceConfig`, `system`, or `toolConfig` fields.
* If you include the `messages` field, the messages are appended after the messages defined in the prompt.
* If you include the `guardrailConfig` field, the guardrail is applied to the entire prompt. If you include `guardContent` blocks in the [ContentBlock][13] field, the guardrail will only be applied to those blocks.
Expand a section to learn more about a field in the `Converse` request body:
#### messages
The `messages` field is an array of [Message][14] objects, each of which defines a message between the user and the model. A `Message` object contains the following fields:
* **role** – Defines whether the message is from the `user` (the prompt sent to the model) or `assistant` (the model response).
* **content** – Defines the content in the prompt.
###### Note
Amazon Bedrock doesn't store any text, images, or documents that you provide as content. The data is only used to generate the response.
You can maintain conversation context by including all the messages in the conversation in subsequent `Converse` requests and using the `role` field to specify whether the message is from the user or the model.
The `content` field maps to an array of [ContentBlock][13] objects. Within each [ContentBlock][13], you can specify one of the following fields (to see what models support what blocks, see [models at a glance][15]):
textThe `text` field maps to a string specifying the prompt. The `text` field is interpreted alongside other fields that are specified in the same [ContentBlock][13].
The following shows a [Message][14] object with a `content` array containing only a text [ContentBlock][13]:

```
{
    "role": "user",
    "content": [
        {
            "text": "string"
        }
    ]
}
```
imageThe `image` field maps to an [ImageBlock][16]. Pass the raw bytes, encoded in base64, for an image in the `bytes` field. If you use an AWS SDK, you don't need to encode the bytes in base64.
If you exclude the `text` field, the model describes the image.
The following shows an example [Message][14] object with a `content` array containing only an image [ContentBlock][13]:

```
{
    "role": "user",
    "content": [
        {
            "image": {
                "format": "png",
                "source": {
                    "bytes": "image in bytes"
                }
            }
        }
    ]
}
```
You can also specify an Amazon S3 URI instead of passing the bytes directly in the request body. The following shows a sample `Message` object with a content array containing the source passed through an Amazon S3 URI.

```
{
    "role": "user",
    "content": [
        {
            "image": {
                "format": "png",
                "source": {
                    "s3Location": {
                        "uri": "s3://amzn-s3-demo-bucket/myImage",
                        "bucketOwner": "111122223333"
                    }
                }
            }
        }
    ]
}
```
documentThe `document` field maps to an [DocumentBlock][17]. If you include a `DocumentBlock`, check that your request conforms to the following restrictions:
* In the `content` field of the [Message][14] object, you must also include a `text` field with a prompt related to the document.
* Pass the raw bytes, encoded in base64, for the document in the `bytes` field. If you use an AWS SDK, you don't need to encode the document bytes in base64.
* The `name` field can only contain the following characters:
 * Alphanumeric characters
 * Whitespace characters (no more than one in a row)
 * Hyphens
 * Parentheses
 * Square brackets
###### Note
The `name` field is vulnerable to prompt injections, because the model might inadvertently interpret it as instructions. Therefore, we recommend that you specify a neutral name.
When using a document you can enable the `citations` tag, which will provide document specific citations in the response of the API call. See the [DocumentBlock][17] API for more details.
The following shows a sample [Message][14] object with a `content` array containing only a document [ContentBlock][13] and a required accompanying text [ContentBlock][13].

```
{
    "role": "user",
    "content": [
        {
            "text": "string"
        },
        {
            "document": {
                "format": "pdf",
                "name": "MyDocument",
                "source": {
                    "bytes": "document in bytes"
                }
            }
        }
    ]
}
```
You can also specify an Amazon S3 URI instead of passing the bytes directly in the request body. The following shows a sample `Message` object with a content array containing the source passed through an Amazon S3 URI.

```
{
    "role": "user",
    "content": [
        {
            "text": "string"
        },
        {
            "document": {
                "format": "pdf",
                "name": "MyDocument",
                "source": {
                    "s3Location": {
                      "uri": "s3://amzn-s3-demo-bucket/myDocument",
                      "bucketOwner": "111122223333"
                    }
                }
            }
        }
    ]
}
```
videoThe `video` field maps to a [VideoBlock][18] object. Pass the raw bytes in the `bytes` field, encoded in base64. If you use the AWS SDK, you don't need to encode the bytes in base64.
If you don't include the `text` field, the model will describe the video.
The following shows a sample [Message][14] object with a `content` array containing only a video [ContentBlock][13].

```
{
    "role": "user",
    "content": [
        {
            "video": {
                "format": "mp4",
                "source": {
                    "bytes": "video in bytes"
                }
            }
        }
    ]
}
```
You can also specify an Amazon S3 URI instead of passing the bytes directly in the request body. The following shows a sample `Message` object with a content array containing the source passed through an Amazon S3 URI.

```
{
    "role": "user",
    "content": [
        {
            "video": {
                "format": "mp4",
                "source": {
                    "s3Location": {
                        "uri": "s3://amzn-s3-demo-bucket/myVideo",
                        "bucketOwner": "111122223333"
                    }
                }
            }
        }
    ]
}
```
###### Note
The assumed role must have the `s3:GetObject` permission to the Amazon S3 URI. The `bucketOwner` field is optional but must be specified if the account making the request does not own the bucket the Amazon S3 URI is found in. For more information, see [Configure access to Amazon S3 buckets][19].
cachePointYou can add cache checkpoints as a block in a message alongside an accompanying prompt by using `cachePoint` fields to use prompt caching. Prompt caching is a feature that lets you begin caching the context of conversations to achieve cost and latency savings. For more information, see [Prompt caching for faster model inference][20].
The following shows a sample [Message][14] object with a `content` array containing a document [ContentBlock][13] and a required accompanying text [ContentBlock][13], as well as a **cachePoint** that adds both the document and text contents to the cache.

```
{
    "role": "user",
    "content": [
        {
            "text": "string"
        },
        {
            "document": {
                "format": "pdf",
                "name": "string",
                "source": {
                    "bytes": "document in bytes"
                }
            }
        },
        {
            "cachePoint": {
                "type": "default"
            }
        }
    ]
}
```
guardContentThe `guardContent` field maps to a [GuardrailConverseContentBlock][21] object. You can use this field to target an input to be evaluated by the guardrail defined in the `guardrailConfig` field. If you don't specify this field, the guardrail evaluates all messages in the request body. You can pass the following types of content in a `GuardBlock`:
* **text** – The following shows an example [Message][14] object with a `content` array containing only a text [GuardrailConverseContentBlock][21]:

```
{
    "role": "user",
    "content": [
        {
            "text": "Tell me what stocks to buy.",
            "qualifiers": [
                "guard_content"
            ]
        }
    ]
}
```
You define the text to be evaluated and include any qualifiers to use for [contextual grounding][22].
* **image** – The following shows a [Message][14] object with a `content` array containing only an image [GuardrailConverseContentBlock][21]:

```
{
    "role": "user",
    "content": [
        {
            "format": "png",
            "source": {
                "bytes": "image in bytes"
            }
        }
    ]
}
```
You specify the format of the image and define the image in bytes.
For more information about using guardrails, see [Detect and filter harmful content by using Amazon Bedrock Guardrails][23].
reasoningContentThe `reasoningContent` field maps to a [ReasoningContentBlock][24]. This block contains content regarding the reasoning that was carried out by the model to generate the response in the accompanying `ContentBlock`.
The following shows a `Message` object with a `content` array containing only a `ReasoningContentBlock` and an accompanying text `ContentBlock`.

```
{
    "role": "user",
    "content": [
        {
            "text": "string"
        },
        {
            "reasoningContent": {
                "reasoningText": {
                    "text": "string",
                    "signature": "string"
                }
                "redactedContent": "base64-encoded binary data object"
            }
        }
    ]
}
```
The `ReasoningContentBlock` contains the reasoning used to generate the accompanying content in the `reasoningText` field, in addition to any content in the reasoning that was encrypted by the model provider for trust and safety reasons in the `redactedContent` field.
Within the `reasoningText` field, the `text` fields describes the reasoning. The `signature` field is a hash of all the messages in the conversation and is a safeguard against tampering of the reasoning used by the model. You must include the signature and all previous messages in subsequent `Converse` requests. If any of the messages are changed, the response throws an error.
toolUseContains information about a tool for the model to use. For more information, see [Use a tool to complete an Amazon Bedrock model response][5].
toolResultContains information about the result from the model using a tool. For more information, see [Use a tool to complete an Amazon Bedrock model response][5].
In the following `messages` example, the user asks for a list of three pop songs, and the model generates a list of songs.

```
[
    {
        "role": "user",
        "content": [
            {
                "text": "Create a list of 3 pop songs."
            }
        ]
    },
    {
        "role": "assistant",
        "content": [
            {
                "text": "Here is a list of 3 pop songs by artists from the United Kingdom:\n\n1. \"As It Was\" by Harry Styles\n2. \"Easy On Me\" by Adele\n3. \"Unholy\" by Sam Smith and Kim Petras"
            }
        ]
    }
]
```
#### system
A system prompt is a type of prompt that provides instructions or context to the model about the task it should perform, or the persona it should adopt during the conversation. You can specify a list of system prompts for the request in the `system` ([SystemContentBlock][25]) field, as shown in the following example.

```
[
    {
        "text": "You are an app that creates play lists for a radio station that plays rock and pop music. Only return song names and the artist. "
    }
]
```
#### inferenceConfig
The Converse API supports a base set of inference parameters that you set in the `inferenceConfig` field ([InferenceConfiguration][26]). The base set of inference parameters are:
* **maxTokens** – The maximum number of tokens to allow in the generated response.
* **stopSequences** – A list of stop sequences. A stop sequence is a sequence of characters that causes the model to stop generating the response.
* **temperature** – The likelihood of the model selecting higher-probability options while generating a response.
* **topP** – The percentage of most-likely candidates that the model considers for the next token.
For more information, see [Influence response generation with inference parameters][27].
The following example JSON sets the `temperature` inference parameter.

```
{"temperature": 0.5}
```
#### additionalModelRequestFields
If the model you are using has additional inference parameters, you can set those parameters by specifying them as JSON in the `additionalModelRequestFields` field. The following example JSON shows how to set `top\_k`, which is available in Anthropic Claude models, but isn't a base inference parameter in the messages API.

```
{"top_k": 200}
```
#### promptVariables
If you specify a prompt from [Prompt management][28] in the `modelId` as the resource to run inference on, use this field to fill in the prompt variables with actual values. The `promptVariables` field maps to a JSON object with keys that correspond to variables defined in the prompts and values to replace the variables with.
For example, let's say that you have a prompt that says `Make me a `{{genre}}` playlist consisting of the following number of songs: `{{number}}`.`. The prompt's ID is `PROMPT12345` and its version is `1`. You could send the following `Converse` request to replace the variables:

```
POST /model/arn:aws:bedrock:us-east-1:111122223333:prompt/PROMPT12345:1/converse HTTP/1.1
Content-type: application/json

{
   "promptVariables": { 
      "genre": {
         "text": "pop"
      },
      "number": {
         "text": "3"
      }
   }
}
```
#### guardrailConfig
You can apply a guardrail that you created with [Amazon Bedrock Guardrails][23] by including this field. To apply the guardrail to a specific message in the conversation, include the message in a [GuardrailConverseContentBlock][21]. If you don't include any `GuardrailConverseContentBlock`s in the request body, the guardrail is applied to all the messages in the `messages` field. For an example, see [Include a guardrail with the Converse API][6].
#### toolConfig
This field lets you define a tool for the model to use to help it generate a response. For more information, see [Use a tool to complete an Amazon Bedrock model response][5].
#### additionalModelResponseFieldPaths
Each model that Amazon Bedrock supports has its own native response shape with provider-specific fields (for example, Anthropic Claude returns a `stop\_sequence` field; Cohere returns `is\_finished`; and so on). To give you a uniform response across models, [Converse][1] and [ConverseStream][2] drop most model-native fields by default and return a normalized envelope with `output`, `stopReason`, `usage`, and `metrics`.
If your application needs one or more of those model-native fields, list their JSON Pointer paths in `additionalModelResponseFieldPaths`. [Converse][1] and [ConverseStream][2] then include those fields in the `additionalModelResponseFields` field of the response.
The following example asks [Converse][1] to also return Anthropic Claude's `stop\_sequence` field, which contains the value of the stop sequence that ended generation:

```
[ "/stop_sequence" ]
```
Each path is a JSON Pointer ([RFC 6901][29]) into the model's native response. Empty pointers and malformed pointers return a `400` error. If a pointer is valid but the requested path doesn't exist in the model's response, it is silently ignored.
###### Note
This field controls which model-native _response fields_ are surfaced through [Converse][1]. It does not control text-output formatting. Some models — particularly reasoning models such as DeepSeek-R1, Claude 3.7 Sonnet with extended thinking, and Amazon Nova reasoning models — can include reasoning content or model-specific tokens in their text output. For how to work with reasoning content, see [Enhance model responses with model reasoning][30].
#### requestMetadata
The `requestMetadata` field maps to a JSON object of key-value tags that are recorded with the request in your model invocation logs. You can use request metadata to filter and aggregate logs by team, application, environment, or any other dimension that varies per call.
The same capability is available on [InvokeModel][3] and [InvokeModelWithResponseStream][4] through the `X-Amzn-Bedrock-Request-Metadata` HTTP header. For details on supported APIs, limits, and how request metadata appears in invocation logs, see [Per-request metadata tagging][31].
#### serviceTier
This field maps to a JSON object. You can specify the service tier for a particular request.
The following example shows the `serviceTier` structure:

```
"serviceTier": {
  "type": "reserved" | "priority" | "default" | "flex"
}
```
For detailed information about service tiers, including pricing and performance characteristics, see [Service tiers for optimizing performance and cost][32].
You can also optionally add cache checkpoints to the `system` or `tools` fields to use prompt caching, depending on which model you're using. For more information, see [Prompt caching for faster model inference][20].
### Response
The response you get from the Converse API depends on which operation you call, `Converse` or `ConverseStream`.
#### Converse response
In the response from `Converse`, the `output` field ([ConverseOutput][33]) contains the message ([Message][14]) that the model generates. The message content is in the `content` ([ContentBlock][13]) field and the role (`user` or `assistant`) that the message corresponds to is in the `role` field.
If you used [prompt caching][20], then in the usage field, `cacheReadInputTokens` and `cacheWriteInputTokens` tell you how many total tokens were read from the cache and written to the cache, respectively.
If you used [service tiers][34], then in the response field, `service tier` would tell you which service tier was used for the request.
The `metrics` field ([ConverseMetrics][35]) includes metrics for the call. To determine why the model stopped generating content, check the `stopReason` field. You can get information about the tokens passed to the model in the request, and the tokens generated in the response, by checking the `usage` field ([TokenUsage][36]). If you specified additional response fields in the request, the API returns them as JSON in the `additionalModelResponseFields` field.
The following example shows the response from `Converse` when you pass the prompt discussed in [Request][37].

```
{
    "output": {
        "message": {
            "role": "assistant",
            "content": [
                {
                    "text": "Here is a list of 3 pop songs by artists from the United Kingdom:\n\n1. \"Wannabe\" by Spice Girls\n2. \"Bitter Sweet Symphony\" by The Verve \n3. \"Don't Look Back in Anger\" by Oasis"
                }
            ]
        }
    },
    "stopReason": "end_turn",
    "usage": {
        "inputTokens": 125,
        "outputTokens": 60,
        "totalTokens": 185
    },
    "metrics": {
        "latencyMs": 1175
    }
}
```
#### ConverseStream response
If you call `ConverseStream` to stream the response from a model, the stream is returned in the `stream` response field. The stream emits the following events. The diagram below shows the order in which the events are received; the content block events repeat once per content block, grouped by `contentBlockIndex`.

```
messageStart                          (once per response)
    |
    v
+-- for each content block (indexed by contentBlockIndex) --+
|                                                           |
|   contentBlockStart    (tool use only)                    |
|   contentBlockDelta    (one or more; text / reasoning /   |
|                         tool use partial JSON)            |
|   contentBlockStop                                        |
|                                                           |
+-----------------------------------------------------------+
    |
    v
messageStop                           (once per response;
    |                                  carries stopReason)
    v
metadata                              (once per response;
                                       usage + metrics)
```
1. `messageStart` ([MessageStartEvent][38]). The start event for a message. Includes the role for the message.
2. `contentBlockStart` ([ContentBlockStartEvent][39]). A Content block start event. Tool use only.
3. `contentBlockDelta` ([ContentBlockDeltaEvent][40]). A Content block delta event. Includes one of the following:
 * `text` – The partial text that the model generates.
 * `reasoningContent` – The partial reasoning carried out by the model to generate the response. You must submit the returned `signature`, in addition to all previous messages in subsequent `Converse` requests. If any of the messages are changed, the response throws an error.
 * `toolUse` – The partial input JSON object for tool use.
4. `contentBlockStop` ([ContentBlockStopEvent][41]). A Content block stop event.
5. `messageStop` ([MessageStopEvent][42]). The stop event for the message. Includes the reason why the model stopped generating output.
6. `metadata` ([ConverseStreamMetadataEvent][43]). Metadata for the request. The metadata includes the token usage in `usage` ([TokenUsage][36]) and metrics for the call in `metrics` ([ConverseStreamMetadataEvent][43]).
ConverseStream streams a complete content block as a `ContentBlockStartEvent` event, one or more `ContentBlockDeltaEvent` events, and a `ContentBlockStopEvent` event. Use the `contentBlockIndex` field as an index to correlate the events that make up a content block.
The following example is a partial response from `ConverseStream`.

```
{'messageStart': {'role': 'assistant'}}
{'contentBlockDelta': {'delta': {'text': ''}, 'contentBlockIndex': 0}}
{'contentBlockDelta': {'delta': {'text': ' Title'}, 'contentBlockIndex': 0}}
{'contentBlockDelta': {'delta': {'text': ':'}, 'contentBlockIndex': 0}}
.
.
.
{'contentBlockDelta': {'delta': {'text': ' The'}, 'contentBlockIndex': 0}}
{'messageStop': {'stopReason': 'max_tokens'}}
{'metadata': {'usage': {'inputTokens': 47, 'outputTokens': 20, 'totalTokens': 67}, 'metrics': {'latencyMs': 100.0}}}

```
## Converse API examples
The following examples show you how to use the `Converse` and `ConverseStream` operations.
TextThis example shows how to call the `Converse` operation with the _Anthropic Claude 3 Sonnet_ model. The example shows how to send the input text, inference parameters, and additional parameters that are unique to the model. The code starts a conversation by asking the model to create a list of songs. It then continues the conversation by asking that the songs are by artists from the United Kingdom.

```
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""
Shows how to use the Converse API with Anthropic Claude 3 Sonnet (on demand).
"""

import logging
import boto3

from botocore.exceptions import ClientError
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
def generate_conversation(bedrock_client,
                          model_id,
                          system_prompts,
                          messages):
    """
    Sends messages to a model.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        system_prompts (JSON) : The system prompts for the model to use.
        messages (JSON) : The messages to send to the model.

    Returns:
        response (JSON): The conversation that the model generated.

    """

    logger.info("Generating message with model %s", model_id)

    # Inference parameters to use.
    temperature = 0.5
    top_k = 200

    # Base inference parameters to use.
    inference_config = {"temperature": temperature}
    # Additional inference parameters to use.
    additional_model_fields = {"top_k": top_k}

    # Send the message.
    response = bedrock_client.converse(
        modelId=model_id,
        messages=messages,
        system=system_prompts,
        inferenceConfig=inference_config,
        additionalModelRequestFields=additional_model_fields
    )

    # Log token usage.
    token_usage = response['usage']
    logger.info("Input tokens: %s", token_usage['inputTokens'])
    logger.info("Output tokens: %s", token_usage['outputTokens'])
    logger.info("Total tokens: %s", token_usage['totalTokens'])
    logger.info("Stop reason: %s", response['stopReason'])

    return response

def main():
    """
    Entrypoint for Anthropic Claude 3 Sonnet example.
    """

    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s: %(message)s")

    model_id = "anthropic.claude-3-sonnet-20240229-v1:0"

    # Setup the system prompts and messages to send to the model.
    system_prompts = [{"text": "You are an app that creates playlists for a radio station that plays rock and pop music. Only return song names and the artist."}]
    message_1 = {
        "role": "user",
        "content": [{"text": "Create a list of 3 pop songs."}]
    }
    message_2 = {
        "role": "user",
        "content": [{"text": "Make sure the songs are by artists from the United Kingdom."}]
    }
    messages = []

    try:

        bedrock_client = boto3.client(service_name='bedrock-runtime')

        # Start the conversation with the 1st message.
        messages.append(message_1)
        response = generate_conversation(
            bedrock_client, model_id, system_prompts, messages)

        # Add the response message to the conversation.
        output_message = response['output']['message']
        messages.append(output_message)

        # Continue the conversation with the 2nd message.
        messages.append(message_2)
        response = generate_conversation(
            bedrock_client, model_id, system_prompts, messages)

        output_message = response['output']['message']
        messages.append(output_message)

        # Show the complete conversation.
        for message in messages:
            print(f"Role: {message['role']}")
            for content in message['content']:
                print(f"Text: {content['text']}")
            print()

    except ClientError as err:
        message = err.response['Error']['Message']
        logger.error("A client error occurred: %s", message)
        print(f"A client error occured: {message}")

    else:
        print(
            f"Finished generating text with model {model_id}.")
if __name__ == "__main__":
    main()

```
ImageThis example shows how to send an image as part of a message and requests that the model describe the image. The example uses `Converse` operation and the _Anthropic Claude 3 Sonnet_ model.

```
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""
Shows how to send an image with the Converse API with an accompanying text prompt to Anthropic Claude 3 Sonnet (on demand).
"""

import logging
import boto3
from botocore.exceptions import ClientError
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
def generate_conversation(bedrock_client,
                          model_id,
                          input_text,
                          input_image):
    """
    Sends a message to a model.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        input text : The text prompt accompanying the image.
        input_image : The path to the input image.

    Returns:
        response (JSON): The conversation that the model generated.

    """

    logger.info("Generating message with model %s", model_id)

    # Get image extension and read in image as bytes
    image_ext = input_image.split(".")[-1]
    with open(input_image, "rb") as f:
        image = f.read()

    message = {
        "role": "user",
        "content": [
            {
                "text": input_text
            },
            {
                "image": {
                    "format": image_ext,
                    "source": {
                        "bytes": image
                    }
                }
            }
        ]
    }

    messages = [message]

    # Send the message.
    response = bedrock_client.converse(
        modelId=model_id,
        messages=messages
    )

    return response
def main():
    """
    Entrypoint for Anthropic Claude 3 Sonnet example.
    """

    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s: %(message)s")

    model_id = "anthropic.claude-3-sonnet-20240229-v1:0"
    input_text = "What's in this image?"
    input_image = "path/to/image"

    try:

        bedrock_client = boto3.client(service_name="bedrock-runtime")

        response = generate_conversation(
            bedrock_client, model_id, input_text, input_image)

        output_message = response['output']['message']

        print(f"Role: {output_message['role']}")

        for content in output_message['content']:
            print(f"Text: {content['text']}")

        token_usage = response['usage']
        print(f"Input tokens:  {token_usage['inputTokens']}")
        print(f"Output tokens:  {token_usage['outputTokens']}")
        print(f"Total tokens:  {token_usage['totalTokens']}")
        print(f"Stop reason: {response['stopReason']}")

    except ClientError as err:
        message = err.response['Error']['Message']
        logger.error("A client error occurred: %s", message)
        print(f"A client error occured: {message}")

    else:
        print(
            f"Finished generating text with model {model_id}.")
if __name__ == "__main__":
    main()

```
DocumentThis example shows how to send a document as part of a message and requests that the model describe the contents of the document. The example uses `Converse` operation and the _Anthropic Claude 3 Sonnet_ model.

```
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""
Shows how to send an document as part of a message to Anthropic Claude 3 Sonnet (on demand).
"""

import logging
import boto3
from botocore.exceptions import ClientError
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
def generate_message(bedrock_client,
                     model_id,
                     input_text,
                     input_document_path):
    """
    Sends a message to a model.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        input text : The input message.
        input_document_path : The path to the input document.

    Returns:
        response (JSON): The conversation that the model generated.

    """

    logger.info("Generating message with model %s", model_id)

    # Get format from path and read the path
    input_document_format = input_document_path.split(".")[-1]
    with open(input_document_path, 'rb') as input_document_file:
        input_document = input_document_file.read()

    # Message to send.
    message = {
        "role": "user",
        "content": [
            {
                "text": input_text
            },
            {
                "document": {
                    "name": "MyDocument",
                    "format": input_document_format,
                    "source": {
                        "bytes": input_document
                    }
                }
            }
        ]
    }

    messages = [message]

    # Send the message.
    response = bedrock_client.converse(
        modelId=model_id,
        messages=messages
    )

    return response
def main():
    """
    Entrypoint for Anthropic Claude 3 Sonnet example.
    """

    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s: %(message)s")

    model_id = "anthropic.claude-3-sonnet-20240229-v1:0"
    input_text = "What's in this document?"
    input_document_path = "path/to/document"

    try:

        bedrock_client = boto3.client(service_name="bedrock-runtime")
        response = generate_message(
            bedrock_client, model_id, input_text, input_document_path)

        output_message = response['output']['message']

        print(f"Role: {output_message['role']}")

        for content in output_message['content']:
            print(f"Text: {content['text']}")

        token_usage = response['usage']
        print(f"Input tokens:  {token_usage['inputTokens']}")
        print(f"Output tokens:  {token_usage['outputTokens']}")
        print(f"Total tokens:  {token_usage['totalTokens']}")
        print(f"Stop reason: {response['stopReason']}")

    except ClientError as err:
        message = err.response['Error']['Message']
        logger.error("A client error occurred: %s", message)
        print(f"A client error occured: {message}")

    else:
        print(
            f"Finished generating text with model {model_id}.")
if __name__ == "__main__":
    main()
```
StreamingThis example shows how to call the `ConverseStream` operation with the _Anthropic Claude 3 Sonnet_ model. The example shows how to send the input text, inference parameters, and additional parameters that are unique to the model.

```
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""
Shows how to use the Converse API to stream a response from Anthropic Claude 3 Sonnet (on demand).
"""

import logging
import boto3
from botocore.exceptions import ClientError
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
def stream_conversation(bedrock_client,
                    model_id,
                    messages,
                    system_prompts,
                    inference_config,
                    additional_model_fields):
    """
    Sends messages to a model and streams the response.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        messages (JSON) : The messages to send.
        system_prompts (JSON) : The system prompts to send.
        inference_config (JSON) : The inference configuration to use.
        additional_model_fields (JSON) : Additional model fields to use.

    Returns:
        Nothing.

    """

    logger.info("Streaming messages with model %s", model_id)

    response = bedrock_client.converse_stream(
        modelId=model_id,
        messages=messages,
        system=system_prompts,
        inferenceConfig=inference_config,
        additionalModelRequestFields=additional_model_fields
    )

    stream = response.get('stream')
    if stream:
        for event in stream:

            if 'messageStart' in event:
                print(f"\nRole: {event['messageStart']['role']}")

            if 'contentBlockDelta' in event:
                print(event['contentBlockDelta']['delta']['text'], end="")

            if 'messageStop' in event:
                print(f"\nStop reason: {event['messageStop']['stopReason']}")

            if 'metadata' in event:
                metadata = event['metadata']
                if 'usage' in metadata:
                    print("\nToken usage")
                    print(f"Input tokens: {metadata['usage']['inputTokens']}")
                    print(
                        f":Output tokens: {metadata['usage']['outputTokens']}")
                    print(f":Total tokens: {metadata['usage']['totalTokens']}")
                if 'metrics' in event['metadata']:
                    print(
                        f"Latency: {metadata['metrics']['latencyMs']} milliseconds")
def main():
    """
    Entrypoint for streaming message API response example.
    """

    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s: %(message)s")

    model_id = "anthropic.claude-3-sonnet-20240229-v1:0"
    system_prompt = """You are an app that creates playlists for a radio station
      that plays rock and pop music. Only return song names and the artist."""

    # Message to send to the model.
    input_text = "Create a list of 3 pop songs."

    message = {
        "role": "user",
        "content": [{"text": input_text}]
    }
    messages = [message]
    
    # System prompts.
    system_prompts = [{"text" : system_prompt}]

    # inference parameters to use.
    temperature = 0.5
    top_k = 200
    # Base inference parameters.
    inference_config = {
        "temperature": temperature
    }
    # Additional model inference parameters.
    additional_model_fields = {"top_k": top_k}

    try:
        bedrock_client = boto3.client(service_name='bedrock-runtime')

        stream_conversation(bedrock_client, model_id, messages,
                        system_prompts, inference_config, additional_model_fields)

    except ClientError as err:
        message = err.response['Error']['Message']
        logger.error("A client error occurred: %s", message)
        print("A client error occured: " +
              format(message))

    else:
        print(
            f"Finished streaming messages with model {model_id}.")
if __name__ == "__main__":
    main()

```
VideoThis example shows how to send a video as part of a message and requests that the model describes the video. The example uses `Converse` operation and the Amazon Nova Pro model.

```
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""
Shows how to send a video with the Converse API to Amazon Nova Pro (on demand).
"""

import logging
import boto3
from botocore.exceptions import ClientError
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
def generate_conversation(bedrock_client,
                          model_id,
                          input_text,
                          input_video):
    """
    Sends a message to a model.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        input text : The input message.
        input_video : The input video.

    Returns:
        response (JSON): The conversation that the model generated.

    """

    logger.info("Generating message with model %s", model_id)

    # Message to send.

    with open(input_video, "rb") as f:
        video = f.read()

    message = {
        "role": "user",
        "content": [
            {
                "text": input_text
            },
            {
                    "video": {
                        "format": 'mp4',
                        "source": {
                            "bytes": video
                        }
                    }
            }
        ]
    }

    messages = [message]

    # Send the message.
    response = bedrock_client.converse(
        modelId=model_id,
        messages=messages
    )

    return response
def main():
    """
    Entrypoint for Amazon Nova Pro example.
    """

    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s: %(message)s")

    model_id = "amazon.nova-pro-v1:0"
    input_text = "What's in this video?"
    input_video = "path/to/video"

    try:

        bedrock_client = boto3.client(service_name="bedrock-runtime")

        response = generate_conversation(
            bedrock_client, model_id, input_text, input_video)

        output_message = response['output']['message']

        print(f"Role: {output_message['role']}")

        for content in output_message['content']:
            print(f"Text: {content['text']}")

        token_usage = response['usage']
        print(f"Input tokens:  {token_usage['inputTokens']}")
        print(f"Output tokens:  {token_usage['outputTokens']}")
        print(f"Total tokens:  {token_usage['totalTokens']}")
        print(f"Stop reason: {response['stopReason']}")

    except ClientError as err:
        message = err.response['Error']['Message']
        logger.error("A client error occurred: %s", message)
        print(f"A client error occured: {message}")

    else:
        print(
            f"Finished generating text with model {model_id}.")
if __name__ == "__main__":
    main()
```
[Document Conventions][44]Invoke APIInference parameters
[1]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html
[2]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html
[3]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModel.html
[4]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModelWithResponseStream.html
[5]: ./tool-use.html
[6]: ./guardrails-use-converse-api.html
[7]: ./inference-api-restrictions.html
[8]: ./conversation-inference-examples.html
[9]: ./service_code_examples_bedrock-runtime.html
[10]: https://community.aws/content/2hUiEkO83hpoGF5nm3FWrdfYvPt/amazon-bedrock-converse-api-java-developer-guide
[11]: https://community.aws/content/2dtauBCeDa703x7fDS9Q30MJoBA/amazon-bedrock-converse-api-developer-guide
[12]: https://docs.aws.amazon.com/general/latest/gr/bedrock.html#br-rt
[13]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ContentBlock.html
[14]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Message.html
[15]: ./model-cards.html
[16]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ImageBlock.html
[17]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_DocumentBlock.html
[18]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_VideoBlock.html
[19]: ./s3-bucket-access.html
[20]: ./prompt-caching.html
[21]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_GuardrailConverseContentBlock.html
[22]: ./guardrails-contextual-grounding-check.html
[23]: ./guardrails.html
[24]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ReasoningContentBlock.html
[25]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_SystemContentBlock.html
[26]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InferenceConfiguration.html
[27]: ./inference-parameters.html
[28]: ./prompt-management.html
[29]: https://datatracker.ietf.org/doc/html/rfc6901
[30]: ./inference-reasoning.html
[31]: ./cost-mgmt-request-metadata.html
[32]: ./service-tiers-inference.html
[33]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseOutput.html
[34]: #inference-service-tiers
[35]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseMetrics.html
[36]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_TokenUsage.html
[37]: #conversation-inference-call-request
[38]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_MessageStartEvent.html
[39]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ContentBlockStartEvent.html
[40]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ContentBlockDeltaEvent.html
[41]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ContentBlockStopEvent.html
[42]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_MessageStopEvent.html
[43]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStreamMetadataEvent.html
[44]: /general/latest/gr/docconventions.html

===== PAGE 1 (2244 chars) =====
# Use a tool to complete an Amazon Bedrock model response
You can use the Amazon Bedrock API to give a model access to tools that can help it generate responses for messages that you send to the model. For example, you might have a chat application that lets users find out the most popular song played on a radio station. To answer a request for the most popular song, a model needs a tool that can query and return the song information.
###### Note
You can now use structured outputs with tool use. See [Get validated JSON results from models][1] for more details.
In Amazon Bedrock, the model doesn't directly call the tool. When you send a message, you also supply definitions for one or more tools that could help the model generate a response. The model decides when a tool is needed; your application code (or Amazon Bedrock itself, in server-side mode) executes the tool and returns the result for the model to incorporate in its final response.
Amazon Bedrock supports three modes of tool use, depending on which API you call and which model family you use:
|**Mode**|**Who runs the tool**|**When to use it**|
|---|---|---|
|[Client-side tool use][2]|Your application code, after the model returns a tool-call request.|Most use cases. Available with the Responses, Chat Completions, Converse, and InvokeModel APIs.|
|[Server-side tool use][3]|Amazon Bedrock itself. You register a Lambda function or AgentCore Gateway, and Amazon Bedrock invokes the tool on the model's behalf.|Centralized, secure tool execution without managing orchestration in your application. Currently available on the Responses API.|
|[Anthropic Claude tool use][4]|Your application code, using Anthropic-defined tool types \(`computer\_\*`, `bash\_\*`, `text\_editor\_\*`, `memory\_\*`\) and the Anthropic Messages API request format.|Computer use, code execution, file editing, persistent memory, or fine-grained tool streaming with Claude models on `bedrock-runtime` or `bedrock-mantle`.|

[Document Conventions][5]Supported Regions and modelsClient-side
[1]: ./structured-output.html
[2]: ./tool-use-client-side.html
[3]: ./tool-use-server-side.html
[4]: ./model-parameters-anthropic-claude-messages-tool-use.html
[5]: /general/latest/gr/docconventions.html

===== PAGE 2 (43773 chars) =====
# Converse
Sends messages to the specified Amazon Bedrock model. `Converse` provides a consistent interface that works with all models that support messages. This allows you to write code once and use it with different models. If a model has unique inference parameters, you can also pass those unique parameters to the model.
Amazon Bedrock doesn't store any text, images, or documents that you provide as content. The data is only used to generate the response.
You can submit a prompt by including it in the `messages` field, specifying the `modelId` of a foundation model or inference profile to run inference on it, and including any other fields that are relevant to your use case.
You can also submit a prompt from Prompt management by specifying the ARN of the prompt version and including a map of variables to values in the `promptVariables` field. You can append more messages to the prompt by using the `messages` field. If you use a prompt from Prompt management, you can't include the following fields in the request: `additionalModelRequestFields`, `inferenceConfig`, `system`, or `toolConfig`. Instead, these fields must be defined through Prompt management. For more information, see [Test a prompt using Prompt management][1].
For information about the Converse API, see [Use the Converse API][2]. To use a guardrail, see  [Use a guardrail with the Converse API][3]. To use a tool with a model, see [Tool use \(Function calling\)][4].
For example code, see [Converse API examples][5].
This operation requires permission for the `bedrock:InvokeModel` action.
###### Important
To deny all inference access to resources that you specify in the modelId field, you need to deny access to the `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream` actions. Doing this also denies access to the resource through the base inference actions ([InvokeModel][6] and [InvokeModelWithResponseStream][7]). For more information see [Deny access for inference on specific models][8].
For troubleshooting some of the common errors you might encounter when using the `Converse` API, see [Troubleshooting Amazon Bedrock API Error Codes][9] in the Amazon Bedrock User Guide
## Request Syntax

```
POST /model/modelId/converse HTTP/1.1
Content-type: application/json

{
   "[additionalModelRequestFields][10]": JSON value,
   "[additionalModelResponseFieldPaths][11]": [ "string" ],
   "[guardrailConfig][12]": { 
      "[guardrailIdentifier][13]": "string",
      "[guardrailVersion][14]": "string",
      "[trace][15]": "string"
   },
   "[inferenceConfig][16]": { 
      "[maxTokens][17]": number,
      "[stopSequences][18]": [ "string" ],
      "[temperature][19]": number,
      "[topP][20]": number
   },
   "[messages][21]": [ 
      { 
         "[content][22]": [ 
            { ... }
         ],
         "[role][23]": "string"
      }
   ],
   "[outputConfig][24]": { 
      "[textFormat][25]": { 
         "[structure][26]": { ... },
         "[type][27]": "string"
      }
   },
   "[performanceConfig][28]": { 
      "[latency][29]": "string"
   },
   "[promptVariables][30]": { 
      "string" : { ... }
   },
   "[requestMetadata][31]": { 
      "string" : "string" 
   },
   "[serviceTier][32]": { 
      "[type][33]": "string"
   },
   "[system][34]": [ 
      { ... }
   ],
   "[toolConfig][35]": { 
      "[toolChoice][36]": { ... },
      "[tools][37]": [ 
         { ... }
      ]
   }
}
```
## URI Request Parameters
The request uses the following URI parameters.
**[modelId][38]**Specifies the model or throughput with which to run inference, or the prompt resource to use in inference. The value depends on the resource that you use:
* If you use a base model, specify the model ID or its ARN. For a list of model IDs for base models, see [Amazon Bedrock base model IDs \(on-demand throughput\)][39] in the Amazon Bedrock User Guide.
* If you use an Amazon Bedrock Marketplace model, specify the ID or ARN of the marketplace endpoint that you created. For more information about Amazon Bedrock Marketplace and setting up an endpoint, see [Amazon Bedrock Marketplace][40] in the Amazon Bedrock User Guide.
* If you use an inference profile, specify the inference profile ID or its ARN. For a list of inference profile IDs, see [Supported Regions and models for cross-region inference][41] in the Amazon Bedrock User Guide.
* If you use a prompt created through [Prompt management][42], specify the ARN of the prompt version. For more information, see [Test a prompt using Prompt management][1].
* If you use a provisioned model, specify the ARN of the Provisioned Throughput. For more information, see [Run inference using a Provisioned Throughput][43] in the Amazon Bedrock User Guide.
* If you use a custom model, specify the ARN of the custom model deployment (for on-demand inference) or the ARN of your provisioned model (for Provisioned Throughput). For more information, see [Use a custom model in Amazon Bedrock][44] in the Amazon Bedrock User Guide.
Length Constraints: Minimum length of 1. Maximum length of 2048.
Pattern: `\(arn:aws\(-\[^:\]+\)?:bedrock:\[a-z0-9-\]{1,20}:\(\(\[0-9\]{12}:custom-model/\[a-z0-9-\]{1,63}\[.\]{1}\[a-z0-9-\]{1,63}/\[a-z0-9\]{12}\)|\(:foundation-model/\[a-z0-9-\]{1,63}\[.\]{1}\[a-z0-9-\]{1,63}\(\[.:\]?\[a-z0-9-\]{1,63}\)\)|\(\[0-9\]{12}:imported-model/\[a-z0-9\]{12}\)|\(\[0-9\]{12}:provisioned-model/\[a-z0-9\]{12}\)|\(\[0-9\]{12}:custom-model-deployment/\[a-z0-9\]{12}\)|\(\[0-9\]{12}:\(inference-profile|application-inference-profile\)/\[a-zA-Z0-9-:.\]+\)\)\)|\(\[a-z0-9-\]{1,63}\[.\]{1}\[a-z0-9-\]{1,63}\(\[.:\]?\[a-z0-9-\]{1,63}\)\)|\(\(\[0-9a-zA-Z\]\[\_-\]?\)+\)|\(\[a-zA-Z0-9-:.\]+\)|\(^\(arn:aws\(-\[^:\]+\)?:bedrock:\[a-z0-9-\]{1,20}:\[0-9\]{12}:prompt/\[0-9a-zA-Z\]{10}\(?::\[0-9\]{1,5}\)?\)\)$|\(^arn:aws:sagemaker:\[a-z0-9-\]+:\[0-9\]{12}:endpoint/\[a-zA-Z0-9-\]+$\)|\(^arn:aws\(-\[^:\]+\)?:bedrock:\(\[0-9a-z-\]{1,20}\):\(\[0-9\]{12}\):\(default-\)?prompt-router/\[a-zA-Z0-9-:.\]+$\)`
Required: Yes
## Request Body
The request accepts the following data in JSON format.
**[additionalModelRequestFields][38]**Additional inference parameters that the model supports, beyond the base set of inference parameters that `Converse` and `ConverseStream` support in the `inferenceConfig` field. For more information, see [Model parameters][45].
Type: JSON value
Required: No
**[additionalModelResponseFieldPaths][38]**Additional model parameters field paths to return in the response. `Converse` and `ConverseStream` return the requested fields as a JSON Pointer object in the `additionalModelResponseFields` field. The following is example JSON for `additionalModelResponseFieldPaths`.
`\[ "/stop\_sequence" \]`
For information about the JSON Pointer syntax, see the [Internet Engineering Task Force (IETF)][46] documentation.
`Converse` and `ConverseStream` reject an empty JSON Pointer or incorrectly structured JSON Pointer with a `400` error code. if the JSON Pointer is valid, but the requested field is not in the model response, it is ignored by `Converse`.
Type: Array of strings
Array Members: Minimum number of 0 items. Maximum number of 10 items.
Length Constraints: Minimum length of 1. Maximum length of 256.
Required: No
**[guardrailConfig][38]**Configuration information for a guardrail that you want to use in the request. If you include `guardContent` blocks in the `content` field in the `messages` field, the guardrail operates only on those messages. If you include no `guardContent` blocks, the guardrail operates on all messages in the request body and in any included prompt resource.
Type: [GuardrailConfiguration][47] object
Required: No
**[inferenceConfig][38]**Inference parameters to pass to the model. `Converse` and `ConverseStream` support a base set of inference parameters. If you need to pass additional parameters that the model supports, use the `additionalModelRequestFields` request field.
Type: [InferenceConfiguration][48] object
Required: No
**[messages][38]**The messages that you want to send to the model.
Type: Array of [Message][49] objects
Required: No
**[outputConfig][38]**Output configuration for a model response.
Type: [OutputConfig][50] object
Required: No
**[performanceConfig][38]**Model performance settings for the request.
Type: [PerformanceConfiguration][51] object
Required: No
**[promptVariables][38]**Contains a map of variables in a prompt from Prompt management to objects containing the values to fill in for them when running model invocation. This field is ignored if you don't specify a prompt resource in the `modelId` field.
Type: String to [PromptVariableValues][52] object map
Required: No
**[requestMetadata][38]**Key-value pairs that you can use to filter invocation logs.
Type: String to string map
Map Entries: Maximum number of 16 items.
Key Length Constraints: Minimum length of 1. Maximum length of 256.
Key Pattern: `\[a-zA-Z0-9\\s:\_@$#=/+,-.\]{1,256}`
Value Length Constraints: Minimum length of 0. Maximum length of 256.
Value Pattern: `\[a-zA-Z0-9\\s:\_@$#=/+,-.\]{0,256}`
Required: No
**[serviceTier][38]**Specifies the processing tier configuration used for serving the request.
Type: [ServiceTier][53] object
Required: No
**[system][38]**A prompt that provides instructions or context to the model about the task it should perform, or the persona it should adopt during the conversation.
Type: Array of [SystemContentBlock][54] objects
Required: No
**[toolConfig][38]**Configuration information for the tools that the model can use when generating a response.
For information about models that support tool use, see [Supported models and model features][55].
Type: [ToolConfiguration][56] object
Required: No
## Response Syntax

```
HTTP/1.1 200
Content-type: application/json

{
   "[additionalModelResponseFields][57]": **_JSON value_**,
   "[metrics][58]": { 
      "[latencyMs][59]": **_number_**
   },
   "[output][60]": { ... },
   "[performanceConfig][61]": { 
      "[latency][29]": "**_string_**"
   },
   "[serviceTier][62]": { 
      "[type][33]": "**_string_**"
   },
   "[stopReason][63]": "**_string_**",
   "[trace][64]": { 
      "[guardrail][65]": { 
         "[actionReason][66]": "**_string_**",
         "[inputAssessment][67]": { 
            "**_string_**" : { 
               "[appliedGuardrailDetails][68]": { 
                  "[guardrailArn][69]": "**_string_**",
                  "[guardrailId][70]": "**_string_**",
                  "[guardrailOrigin][71]": [ "**_string_**" ],
                  "[guardrailOwnership][72]": "**_string_**",
                  "[guardrailVersion][73]": "**_string_**"
               },
               "[automatedReasoningPolicy][74]": { 
                  "[findings][75]": [ 
                     { ... }
                  ]
               },
               "[contentPolicy][76]": { 
                  "[filters][77]": [ 
                     { 
                        "[action][78]": "**_string_**",
                        "[confidence][79]": "**_string_**",
                        "[detected][80]": **_boolean_**,
                        "[filterStrength][81]": "**_string_**",
                        "[type][82]": "**_string_**"
                     }
                  ]
               },
               "[contextualGroundingPolicy][83]": { 
                  "[filters][84]": [ 
                     { 
                        "[action][85]": "**_string_**",
                        "[detected][86]": **_boolean_**,
                        "[score][87]": **_number_**,
                        "[threshold][88]": **_number_**,
                        "[type][89]": "**_string_**"
                     }
                  ]
               },
               "[invocationMetrics][90]": { 
                  "[guardrailCoverage][91]": { 
                     "[images][92]": { 
                        "[guarded][93]": **_number_**,
                        "[total][94]": **_number_**
                     },
                     "[textCharacters][95]": { 
                        "[guarded][96]": **_number_**,
                        "[total][97]": **_number_**
                     }
                  },
                  "[guardrailProcessingLatency][98]": **_number_**,
                  "[usage][99]": { 
                     "[automatedReasoningPolicies][100]": **_number_**,
                     "[automatedReasoningPolicyUnits][101]": **_number_**,
                     "[contentPolicyImageUnits][102]": **_number_**,
                     "[contentPolicyUnits][103]": **_number_**,
                     "[contextualGroundingPolicyUnits][104]": **_number_**,
                     "[sensitiveInformationPolicyFreeUnits][105]": **_number_**,
                     "[sensitiveInformationPolicyUnits][106]": **_number_**,
                     "[topicPolicyUnits][107]": **_number_**,
                     "[wordPolicyUnits][108]": **_number_**
                  }
               },
               "[sensitiveInformationPolicy][109]": { 
                  "[piiEntities][110]": [ 
                     { 
                        "[action][111]": "**_string_**",
                        "[detected][112]": **_boolean_**,
                        "[match][113]": "**_string_**",
                        "[type][114]": "**_string_**"
                     }
                  ],
                  "[regexes][115]": [ 
                     { 
                        "[action][116]": "**_string_**",
                        "[detected][117]": **_boolean_**,
                        "[match][118]": "**_string_**",
                        "[name][119]": "**_string_**",
                        "[regex][120]": "**_string_**"
                     }
                  ]
               },
               "[topicPolicy][121]": { 
                  "[topics][122]": [ 
                     { 
                        "[action][123]": "**_string_**",
                        "[detected][124]": **_boolean_**,
                        "[name][125]": "**_string_**",
                        "[type][126]": "**_string_**"
                     }
                  ]
               },
               "[wordPolicy][127]": { 
                  "[customWords][128]": [ 
                     { 
                        "[action][129]": "**_string_**",
                        "[detected][130]": **_boolean_**,
                        "[match][131]": "**_string_**"
                     }
                  ],
                  "[managedWordLists][132]": [ 
                     { 
                        "[action][133]": "**_string_**",
                        "[detected][134]": **_boolean_**,
                        "[match][135]": "**_string_**",
                        "[type][136]": "**_string_**"
                     }
                  ]
               }
            }
         },
         "[modelOutput][137]": [ "**_string_**" ],
         "[outputAssessments][138]": { 
            "**_string_**" : [ 
               { 
                  "[appliedGuardrailDetails][68]": { 
                     "[guardrailArn][69]": "**_string_**",
                     "[guardrailId][70]": "**_string_**",
                     "[guardrailOrigin][71]": [ "**_string_**" ],
                     "[guardrailOwnership][72]": "**_string_**",
                     "[guardrailVersion][73]": "**_string_**"
                  },
                  "[automatedReasoningPolicy][74]": { 
                     "[findings][75]": [ 
                        { ... }
                     ]
                  },
                  "[contentPolicy][76]": { 
                     "[filters][77]": [ 
                        { 
                           "[action][78]": "**_string_**",
                           "[confidence][79]": "**_string_**",
                           "[detected][80]": **_boolean_**,
                           "[filterStrength][81]": "**_string_**",
                           "[type][82]": "**_string_**"
                        }
                     ]
                  },
                  "[contextualGroundingPolicy][83]": { 
                     "[filters][84]": [ 
                        { 
                           "[action][85]": "**_string_**",
                           "[detected][86]": **_boolean_**,
                           "[score][87]": **_number_**,
                           "[threshold][88]": **_number_**,
                           "[type][89]": "**_string_**"
                        }
                     ]
                  },
                  "[invocationMetrics][90]": { 
                     "[guardrailCoverage][91]": { 
                        "[images][92]": { 
                           "[guarded][93]": **_number_**,
                           "[total][94]": **_number_**
                        },
                        "[textCharacters][95]": { 
                           "[guarded][96]": **_number_**,
                           "[total][97]": **_number_**
                        }
                     },
                     "[guardrailProcessingLatency][98]": **_number_**,
                     "[usage][99]": { 
                        "[automatedReasoningPolicies][100]": **_number_**,
                        "[automatedReasoningPolicyUnits][101]": **_number_**,
                        "[contentPolicyImageUnits][102]": **_number_**,
                        "[contentPolicyUnits][103]": **_number_**,
                        "[contextualGroundingPolicyUnits][104]": **_number_**,
                        "[sensitiveInformationPolicyFreeUnits][105]": **_number_**,
                        "[sensitiveInformationPolicyUnits][106]": **_number_**,
                        "[topicPolicyUnits][107]": **_number_**,
                        "[wordPolicyUnits][108]": **_number_**
                     }
                  },
                  "[sensitiveInformationPolicy][109]": { 
                     "[piiEntities][110]": [ 
                        { 
                           "[action][111]": "**_string_**",
                           "[detected][112]": **_boolean_**,
                           "[match][113]": "**_string_**",
                           "[type][114]": "**_string_**"
                        }
                     ],
                     "[regexes][115]": [ 
                        { 
                           "[action][116]": "**_string_**",
                           "[detected][117]": **_boolean_**,
                           "[match][118]": "**_string_**",
                           "[name][119]": "**_string_**",
                           "[regex][120]": "**_string_**"
                        }
                     ]
                  },
                  "[topicPolicy][121]": { 
                     "[topics][122]": [ 
                        { 
                           "[action][123]": "**_string_**",
                           "[detected][124]": **_boolean_**,
                           "[name][125]": "**_string_**",
                           "[type][126]": "**_string_**"
                        }
                     ]
                  },
                  "[wordPolicy][127]": { 
                     "[customWords][128]": [ 
                        { 
                           "[action][129]": "**_string_**",
                           "[detected][130]": **_boolean_**,
                           "[match][131]": "**_string_**"
                        }
                     ],
                     "[managedWordLists][132]": [ 
                        { 
                           "[action][133]": "**_string_**",
                           "[detected][134]": **_boolean_**,
                           "[match][135]": "**_string_**",
                           "[type][136]": "**_string_**"
                        }
                     ]
                  }
               }
            ]
         }
      },
      "[promptRouter][139]": { 
         "[invokedModelId][140]": "**_string_**"
      }
   },
   "[usage][141]": { 
      "[cacheDetails][142]": [ 
         { 
            "[inputTokens][143]": **_number_**,
            "[ttl][144]": "**_string_**"
         }
      ],
      "[cacheReadInputTokens][145]": **_number_**,
      "[cacheWriteInputTokens][146]": **_number_**,
      "[inputTokens][147]": **_number_**,
      "[outputTokens][148]": **_number_**,
      "[totalTokens][149]": **_number_**
   }
}
```
## Response Elements
If the action is successful, the service sends back an HTTP 200 response.
The following data is returned in JSON format by the service.
**[additionalModelResponseFields][150]**Additional fields in the response that are unique to the model.
Type: JSON value
**[metrics][150]**Metrics for the call to `Converse`.
Type: [ConverseMetrics][151] object
**[output][150]**The result from the call to `Converse`.
Type: [ConverseOutput][152] object
**Note: **This object is a Union. Only one member of this object can be specified or returned.
**[performanceConfig][150]**Model performance settings for the request.
Type: [PerformanceConfiguration][51] object
**[serviceTier][150]**Specifies the processing tier configuration used for serving the request.
Type: [ServiceTier][53] object
**[stopReason][150]**The reason why the model stopped generating output.
Type: String
Valid Values: `end\_turn | tool\_use | max\_tokens | stop\_sequence | guardrail\_intervened | content\_filtered | malformed\_model\_output | malformed\_tool\_use | model\_context\_window\_exceeded`
**[trace][150]**A trace object that contains information about the Guardrail behavior.
Type: [ConverseTrace][153] object
**[usage][150]**The total number of tokens used in the call to `Converse`. The total includes the tokens input to the model and the tokens generated by the model.
Type: [TokenUsage][154] object
## Errors
For information about the errors that are common to all actions, see [Common Error Types][155].
** AccessDeniedException **The request is denied because you do not have sufficient permissions to perform the requested action. For troubleshooting this error, see [AccessDeniedException][156] in the Amazon Bedrock User Guide
HTTP Status Code: 403
** InternalServerException **An internal server error occurred. For troubleshooting this error, see [InternalFailure][157] in the Amazon Bedrock User Guide
HTTP Status Code: 500
** ModelErrorException **The request failed due to an error while processing the model.
** originalStatusCode **The original status code.
** resourceName **The resource name.
HTTP Status Code: 424
** ModelNotReadyException **The model specified in the request is not ready to serve inference requests. The AWS SDK will automatically retry the operation up to 5 times. For information about configuring automatic retries, see [Retry behavior][158] in the _AWS SDKs and Tools_ reference guide.
HTTP Status Code: 429
** ModelTimeoutException **The request took too long to process. Processing time exceeded the model timeout length.
HTTP Status Code: 408
** ResourceNotFoundException **The specified resource ARN was not found. For troubleshooting this error, see [ResourceNotFound][159] in the Amazon Bedrock User Guide
HTTP Status Code: 404
** ServiceUnavailableException **The service isn't currently available. For troubleshooting this error, see [ServiceUnavailable][160] in the Amazon Bedrock User Guide
HTTP Status Code: 503
** ThrottlingException **Your request was denied due to exceeding the account quotas for _Amazon Bedrock_. For troubleshooting this error, see [ThrottlingException][161] in the Amazon Bedrock User Guide
HTTP Status Code: 429
** ValidationException **The input fails to satisfy the constraints specified by _Amazon Bedrock_. For troubleshooting this error, see [ValidationError][162] in the Amazon Bedrock User Guide
HTTP Status Code: 400
## Examples
### Send a message to a model
Send a messsage to Anthropic Claude Sonnet with `Converse`.
#### Sample Request

```
POST /model/anthropic.claude-3-sonnet-20240229-v1:0/converse HTTP/1.1
Content-type: application/json

{
    "messages": [
        {
            "role": "user",
            "content": [
                {
                    "text": "Write an article about impact of high inflation to GDP of a country"
                }
            ]
        }
    ],
    "system": [{"text" : "You are an economist with access to lots of data"}],
    "inferenceConfig": {
        "maxTokens": 1000,
        "temperature": 0.5
    }
}
```
### Example response
Response for the above request.
#### Sample Request

```
HTTP/1.1 200
Content-type: application/json

{
    "output": {
        "message": {
            "content": [
                {
                    "text": ""
                }
            ],
            "role": "assistant"
        }
    },
    "stopReason": "end_turn",
    "usage": {
        "inputTokens": 30,
        "outputTokens": 628,
        "totalTokens": 658
    },
    "metrics": {
        "latencyMs": 1275
    }
}
```
### Send a message with additional model fields
In the following example, the request passess a field (`top\_k`) that the `Converse` field doesn't support. You pass the additional field in the `additionalModelRequestFields` field. The example also shows how to set the paths for the additional fields sent in the response from the model.
#### Sample Request

```
POST /model/anthropic.claude-3-sonnet-20240229-v1:0/converse HTTP/1.1
Content-type: application/json

{
    "messages": [
        {
            "role": "user",
            "content": [
                {
                    "text": "Provide general steps to debug a BSOD on a Windows laptop."
                }
            ]
        }
    ],
    "system": [{"text" : "You are a tech support expert who helps resolve technical issues. Signal 'SUCCESS' if you can resolve the issue, otherwise 'FAILURE'"}],
    "inferenceConfig": {
        "stopSequences": [ "SUCCESS", "FAILURE" ]
    },
    "additionalModelRequestFields": {
        "top_k": 200
    },
    "additionalModelResponseFieldPaths": [
        "/stop_sequence"
    ]
}
```
### Example response
Response for the above example.
#### Sample Request

```
HTTP/1.1 200
Content-type: application/json

{
    "output": {
        "message": {
            "content": [
                {
                    "text": ""
                }
            ],
            "role": "assistant"
        }
    },
    "additionalModelResponseFields": {
        "stop_sequence": "SUCCESS"
    },
    "stopReason": "stop_sequence",
    "usage": {
        "inputTokens": 51,
        "outputTokens": 442,
        "totalTokens": 493
    },
    "metrics": {
        "latencyMs": 7944
    }
}
```
### Use an inference profile in a conversation
The following request calls the US Anthropic Claude 3.5 Sonnet inference profile to route traffic to the us-east-1 and us-west-2 regions.
#### Sample Request

```
POST /model/us.anthropic.claude-3-5-sonnet-20240620-v1:0/converse HTTP/1.1

{
    "messages": [
        {
            "role": "user",
            "content": [
                {
                    "text": "Hello world"
                }
            ]
        }
    ]
}
```
### Run inference on a prompt resource from Prompt management
Send the following request to run inference on version 1 of a prompt resource from Prompt management whose ID is `PROMPT12345`. Suppose the prompt contains a variable called `{{genre}}`. This request would fill in the variable with the value `pop`. Check that you have `bedrock:RenderPrompt` permissions for the prompt resource. For more information, see [Prerequisites for Prompt management][163].
#### Sample Request

```
POST /model/arn:aws:bedrock:us-west-2:123456789012:prompt/PROMPT12345:1/converse HTTP/1.1
Content-type: application/json

{
   "promptVariables": {
      "genre": {
         "text": "pop"
      }
   }
}
```
## See Also
For more information about using this API in one of the language-specific AWS SDKs, see the following:
* [AWS Command Line Interface V2][164]
* [AWS SDK for .NET V4][165]
* [AWS SDK for C++][166]
* [AWS SDK for Go v2][167]
* [AWS SDK for Java V2][168]
* [AWS SDK for JavaScript V3][169]
* [AWS SDK for Kotlin][170]
* [AWS SDK for PHP V3][171]
* [AWS SDK for Python][172]
* [AWS SDK for Ruby V3][173]
[Document Conventions][174]ApplyGuardrailConverseStream
[1]: https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-management-test.html
[2]: https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html
[3]: https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails-use-converse-api.html
[4]: https://docs.aws.amazon.com/bedrock/latest/userguide/tool-use.html
[5]: https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html#message-inference-examples
[6]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModel.html
[7]: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModelWithResponseStream.html
[8]: https://docs.aws.amazon.com/bedrock/latest/userguide/security_iam_id-based-policy-examples.html#security_iam_id-based-policy-examples-deny-inference
[9]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html
[10]: #bedrock-runtime_Converse-request-additionalModelRequestFields
[11]: #bedrock-runtime_Converse-request-additionalModelResponseFieldPaths
[12]: #bedrock-runtime_Converse-request-guardrailConfig
[13]: ./API_runtime_GuardrailConfiguration.html#bedrock-Type-runtime_GuardrailConfiguration-guardrailIdentifier
[14]: ./API_runtime_GuardrailConfiguration.html#bedrock-Type-runtime_GuardrailConfiguration-guardrailVersion
[15]: ./API_runtime_GuardrailConfiguration.html#bedrock-Type-runtime_GuardrailConfiguration-trace
[16]: #bedrock-runtime_Converse-request-inferenceConfig
[17]: ./API_runtime_InferenceConfiguration.html#bedrock-Type-runtime_InferenceConfiguration-maxTokens
[18]: ./API_runtime_InferenceConfiguration.html#bedrock-Type-runtime_InferenceConfiguration-stopSequences
[19]: ./API_runtime_InferenceConfiguration.html#bedrock-Type-runtime_InferenceConfiguration-temperature
[20]: ./API_runtime_InferenceConfiguration.html#bedrock-Type-runtime_InferenceConfiguration-topP
[21]: #bedrock-runtime_Converse-request-messages
[22]: ./API_runtime_Message.html#bedrock-Type-runtime_Message-content
[23]: ./API_runtime_Message.html#bedrock-Type-runtime_Message-role
[24]: #bedrock-runtime_Converse-request-outputConfig
[25]: ./API_runtime_OutputConfig.html#bedrock-Type-runtime_OutputConfig-textFormat
[26]: ./API_runtime_OutputFormat.html#bedrock-Type-runtime_OutputFormat-structure
[27]: ./API_runtime_OutputFormat.html#bedrock-Type-runtime_OutputFormat-type
[28]: #bedrock-runtime_Converse-request-performanceConfig
[29]: ./API_runtime_PerformanceConfiguration.html#bedrock-Type-runtime_PerformanceConfiguration-latency
[30]: #bedrock-runtime_Converse-request-promptVariables
[31]: #bedrock-runtime_Converse-request-requestMetadata
[32]: #bedrock-runtime_Converse-request-serviceTier
[33]: ./API_runtime_ServiceTier.html#bedrock-Type-runtime_ServiceTier-type
[34]: #bedrock-runtime_Converse-request-system
[35]: #bedrock-runtime_Converse-request-toolConfig
[36]: ./API_runtime_ToolConfiguration.html#bedrock-Type-runtime_ToolConfiguration-toolChoice
[37]: ./API_runtime_ToolConfiguration.html#bedrock-Type-runtime_ToolConfiguration-tools
[38]: #API_runtime_Converse_RequestSyntax
[39]: https://docs.aws.amazon.com/bedrock/latest/userguide/model-ids.html
[40]: https://docs.aws.amazon.com/bedrock/latest/userguide/amazon-bedrock-marketplace.html
[41]: https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference-support.html
[42]: https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-management.html
[43]: https://docs.aws.amazon.com/bedrock/latest/userguide/prov-thru-use.html
[44]: https://docs.aws.amazon.com/bedrock/latest/userguide/model-customization-use.html
[45]: https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters.html
[46]: https://datatracker.ietf.org/doc/html/rfc6901
[47]: ./API_runtime_GuardrailConfiguration.html
[48]: ./API_runtime_InferenceConfiguration.html
[49]: ./API_runtime_Message.html
[50]: ./API_runtime_OutputConfig.html
[51]: ./API_runtime_PerformanceConfiguration.html
[52]: ./API_runtime_PromptVariableValues.html
[53]: ./API_runtime_ServiceTier.html
[54]: ./API_runtime_SystemContentBlock.html
[55]: https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html#conversation-inference-supported-models-features
[56]: ./API_runtime_ToolConfiguration.html
[57]: #bedrock-runtime_Converse-response-additionalModelResponseFields
[58]: #bedrock-runtime_Converse-response-metrics
[59]: ./API_runtime_ConverseMetrics.html#bedrock-Type-runtime_ConverseMetrics-latencyMs
[60]: #bedrock-runtime_Converse-response-output
[61]: #bedrock-runtime_Converse-response-performanceConfig
[62]: #bedrock-runtime_Converse-response-serviceTier
[63]: #bedrock-runtime_Converse-response-stopReason
[64]: #bedrock-runtime_Converse-response-trace
[65]: ./API_runtime_ConverseTrace.html#bedrock-Type-runtime_ConverseTrace-guardrail
[66]: ./API_runtime_GuardrailTraceAssessment.html#bedrock-Type-runtime_GuardrailTraceAssessment-actionReason
[67]: ./API_runtime_GuardrailTraceAssessment.html#bedrock-Type-runtime_GuardrailTraceAssessment-inputAssessment
[68]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-appliedGuardrailDetails
[69]: ./API_runtime_AppliedGuardrailDetails.html#bedrock-Type-runtime_AppliedGuardrailDetails-guardrailArn
[70]: ./API_runtime_AppliedGuardrailDetails.html#bedrock-Type-runtime_AppliedGuardrailDetails-guardrailId
[71]: ./API_runtime_AppliedGuardrailDetails.html#bedrock-Type-runtime_AppliedGuardrailDetails-guardrailOrigin
[72]: ./API_runtime_AppliedGuardrailDetails.html#bedrock-Type-runtime_AppliedGuardrailDetails-guardrailOwnership
[73]: ./API_runtime_AppliedGuardrailDetails.html#bedrock-Type-runtime_AppliedGuardrailDetails-guardrailVersion
[74]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-automatedReasoningPolicy
[75]: ./API_runtime_GuardrailAutomatedReasoningPolicyAssessment.html#bedrock-Type-runtime_GuardrailAutomatedReasoningPolicyAssessment-findings
[76]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-contentPolicy
[77]: ./API_runtime_GuardrailContentPolicyAssessment.html#bedrock-Type-runtime_GuardrailContentPolicyAssessment-filters
[78]: ./API_runtime_GuardrailContentFilter.html#bedrock-Type-runtime_GuardrailContentFilter-action
[79]: ./API_runtime_GuardrailContentFilter.html#bedrock-Type-runtime_GuardrailContentFilter-confidence
[80]: ./API_runtime_GuardrailContentFilter.html#bedrock-Type-runtime_GuardrailContentFilter-detected
[81]: ./API_runtime_GuardrailContentFilter.html#bedrock-Type-runtime_GuardrailContentFilter-filterStrength
[82]: ./API_runtime_GuardrailContentFilter.html#bedrock-Type-runtime_GuardrailContentFilter-type
[83]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-contextualGroundingPolicy
[84]: ./API_runtime_GuardrailContextualGroundingPolicyAssessment.html#bedrock-Type-runtime_GuardrailContextualGroundingPolicyAssessment-filters
[85]: ./API_runtime_GuardrailContextualGroundingFilter.html#bedrock-Type-runtime_GuardrailContextualGroundingFilter-action
[86]: ./API_runtime_GuardrailContextualGroundingFilter.html#bedrock-Type-runtime_GuardrailContextualGroundingFilter-detected
[87]: ./API_runtime_GuardrailContextualGroundingFilter.html#bedrock-Type-runtime_GuardrailContextualGroundingFilter-score
[88]: ./API_runtime_GuardrailContextualGroundingFilter.html#bedrock-Type-runtime_GuardrailContextualGroundingFilter-threshold
[89]: ./API_runtime_GuardrailContextualGroundingFilter.html#bedrock-Type-runtime_GuardrailContextualGroundingFilter-type
[90]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-invocationMetrics
[91]: ./API_runtime_GuardrailInvocationMetrics.html#bedrock-Type-runtime_GuardrailInvocationMetrics-guardrailCoverage
[92]: ./API_runtime_GuardrailCoverage.html#bedrock-Type-runtime_GuardrailCoverage-images
[93]: ./API_runtime_GuardrailImageCoverage.html#bedrock-Type-runtime_GuardrailImageCoverage-guarded
[94]: ./API_runtime_GuardrailImageCoverage.html#bedrock-Type-runtime_GuardrailImageCoverage-total
[95]: ./API_runtime_GuardrailCoverage.html#bedrock-Type-runtime_GuardrailCoverage-textCharacters
[96]: ./API_runtime_GuardrailTextCharactersCoverage.html#bedrock-Type-runtime_GuardrailTextCharactersCoverage-guarded
[97]: ./API_runtime_GuardrailTextCharactersCoverage.html#bedrock-Type-runtime_GuardrailTextCharactersCoverage-total
[98]: ./API_runtime_GuardrailInvocationMetrics.html#bedrock-Type-runtime_GuardrailInvocationMetrics-guardrailProcessingLatency
[99]: ./API_runtime_GuardrailInvocationMetrics.html#bedrock-Type-runtime_GuardrailInvocationMetrics-usage
[100]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-automatedReasoningPolicies
[101]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-automatedReasoningPolicyUnits
[102]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-contentPolicyImageUnits
[103]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-contentPolicyUnits
[104]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-contextualGroundingPolicyUnits
[105]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-sensitiveInformationPolicyFreeUnits
[106]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-sensitiveInformationPolicyUnits
[107]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-topicPolicyUnits
[108]: ./API_runtime_GuardrailUsage.html#bedrock-Type-runtime_GuardrailUsage-wordPolicyUnits
[109]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-sensitiveInformationPolicy
[110]: ./API_runtime_GuardrailSensitiveInformationPolicyAssessment.html#bedrock-Type-runtime_GuardrailSensitiveInformationPolicyAssessment-piiEntities
[111]: ./API_runtime_GuardrailPiiEntityFilter.html#bedrock-Type-runtime_GuardrailPiiEntityFilter-action
[112]: ./API_runtime_GuardrailPiiEntityFilter.html#bedrock-Type-runtime_GuardrailPiiEntityFilter-detected
[113]: ./API_runtime_GuardrailPiiEntityFilter.html#bedrock-Type-runtime_GuardrailPiiEntityFilter-match
[114]: ./API_runtime_GuardrailPiiEntityFilter.html#bedrock-Type-runtime_GuardrailPiiEntityFilter-type
[115]: ./API_runtime_GuardrailSensitiveInformationPolicyAssessment.html#bedrock-Type-runtime_GuardrailSensitiveInformationPolicyAssessment-regexes
[116]: ./API_runtime_GuardrailRegexFilter.html#bedrock-Type-runtime_GuardrailRegexFilter-action
[117]: ./API_runtime_GuardrailRegexFilter.html#bedrock-Type-runtime_GuardrailRegexFilter-detected
[118]: ./API_runtime_GuardrailRegexFilter.html#bedrock-Type-runtime_GuardrailRegexFilter-match
[119]: ./API_runtime_GuardrailRegexFilter.html#bedrock-Type-runtime_GuardrailRegexFilter-name
[120]: ./API_runtime_GuardrailRegexFilter.html#bedrock-Type-runtime_GuardrailRegexFilter-regex
[121]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-topicPolicy
[122]: ./API_runtime_GuardrailTopicPolicyAssessment.html#bedrock-Type-runtime_GuardrailTopicPolicyAssessment-topics
[123]: ./API_runtime_GuardrailTopic.html#bedrock-Type-runtime_GuardrailTopic-action
[124]: ./API_runtime_GuardrailTopic.html#bedrock-Type-runtime_GuardrailTopic-detected
[125]: ./API_runtime_GuardrailTopic.html#bedrock-Type-runtime_GuardrailTopic-name
[126]: ./API_runtime_GuardrailTopic.html#bedrock-Type-runtime_GuardrailTopic-type
[127]: ./API_runtime_GuardrailAssessment.html#bedrock-Type-runtime_GuardrailAssessment-wordPolicy
[128]: ./API_runtime_GuardrailWordPolicyAssessment.html#bedrock-Type-runtime_GuardrailWordPolicyAssessment-customWords
[129]: ./API_runtime_GuardrailCustomWord.html#bedrock-Type-runtime_GuardrailCustomWord-action
[130]: ./API_runtime_GuardrailCustomWord.html#bedrock-Type-runtime_GuardrailCustomWord-detected
[131]: ./API_runtime_GuardrailCustomWord.html#bedrock-Type-runtime_GuardrailCustomWord-match
[132]: ./API_runtime_GuardrailWordPolicyAssessment.html#bedrock-Type-runtime_GuardrailWordPolicyAssessment-managedWordLists
[133]: ./API_runtime_GuardrailManagedWord.html#bedrock-Type-runtime_GuardrailManagedWord-action
[134]: ./API_runtime_GuardrailManagedWord.html#bedrock-Type-runtime_GuardrailManagedWord-detected
[135]: ./API_runtime_GuardrailManagedWord.html#bedrock-Type-runtime_GuardrailManagedWord-match
[136]: ./API_runtime_GuardrailManagedWord.html#bedrock-Type-runtime_GuardrailManagedWord-type
[137]: ./API_runtime_GuardrailTraceAssessment.html#bedrock-Type-runtime_GuardrailTraceAssessment-modelOutput
[138]: ./API_runtime_GuardrailTraceAssessment.html#bedrock-Type-runtime_GuardrailTraceAssessment-outputAssessments
[139]: ./API_runtime_ConverseTrace.html#bedrock-Type-runtime_ConverseTrace-promptRouter
[140]: ./API_runtime_PromptRouterTrace.html#bedrock-Type-runtime_PromptRouterTrace-invokedModelId
[141]: #bedrock-runtime_Converse-response-usage
[142]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-cacheDetails
[143]: ./API_runtime_CacheDetail.html#bedrock-Type-runtime_CacheDetail-inputTokens
[144]: ./API_runtime_CacheDetail.html#bedrock-Type-runtime_CacheDetail-ttl
[145]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-cacheReadInputTokens
[146]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-cacheWriteInputTokens
[147]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-inputTokens
[148]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-outputTokens
[149]: ./API_runtime_TokenUsage.html#bedrock-Type-runtime_TokenUsage-totalTokens
[150]: #API_runtime_Converse_ResponseSyntax
[151]: ./API_runtime_ConverseMetrics.html
[152]: ./API_runtime_ConverseOutput.html
[153]: ./API_runtime_ConverseTrace.html
[154]: ./API_runtime_TokenUsage.html
[155]: ./CommonErrors.html
[156]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-access-denied
[157]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-internal-failure
[158]: https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html
[159]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-resource-not-found
[160]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-service-unavailable
[161]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-throttling-exception
[162]: https://docs.aws.amazon.com/bedrock/latest/userguide/troubleshooting-api-error-codes.html#ts-validation-error
[163]: https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-management-prereq.html
[164]: https://docs.aws.amazon.com/goto/cli2/bedrock-runtime-2023-09-30/Converse
[165]: https://docs.aws.amazon.com/goto/DotNetSDKV4/bedrock-runtime-2023-09-30/Converse
[166]: https://docs.aws.amazon.com/goto/SdkForCpp/bedrock-runtime-2023-09-30/Converse
[167]: https://docs.aws.amazon.com/goto/SdkForGoV2/bedrock-runtime-2023-09-30/Converse
[168]: https://docs.aws.amazon.com/goto/SdkForJavaV2/bedrock-runtime-2023-09-30/Converse
[169]: https://docs.aws.amazon.com/goto/SdkForJavaScriptV3/bedrock-runtime-2023-09-30/Converse
[170]: https://docs.aws.amazon.com/goto/SdkForKotlin/bedrock-runtime-2023-09-30/Converse
[171]: https://docs.aws.amazon.com/goto/SdkForPHPV3/bedrock-runtime-2023-09-30/Converse
[172]: https://docs.aws.amazon.com/goto/boto3/bedrock-runtime-2023-09-30/Converse
[173]: https://docs.aws.amazon.com/goto/SdkForRubyV3/bedrock-runtime-2023-09-30/Converse
[174]: /general/latest/gr/docconventions.html

===== PAGE 3 (275 chars) =====
# Using the Converse API \(moved\)
The content of this page has moved to [Inference using Converse API][1].
[Document Conventions][2]Submit a single prompt \(moved\)Converse API examples \(moved\)
[1]: ./conversation-inference.html
[2]: /general/latest/gr/docconventions.html