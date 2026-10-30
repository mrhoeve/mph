# JetBrains Marketplace publishing

MPH releases one signed plugin ZIP to GitHub Releases and JetBrains Marketplace. JetBrains requires the first plugin version to be registered manually; later versions can be uploaded automatically by the release workflow.

## 1. Create the Marketplace vendor

1. Sign in at [JetBrains Marketplace](https://plugins.jetbrains.com/).
2. Accept the Marketplace Developer Agreement.
3. Create or select the vendor profile that will own **Maven Project Helper**.
4. Complete any vendor or trader-status questions shown by JetBrains. MPH remains a free, MIT-licensed plugin.

Do not create a second Marketplace entry if `nl.hicts.mph.plugin` has already been registered. The plugin XML ID must remain stable because IntelliJ uses it to identify updates.

## 2. Generate signing material once

Run these commands on a trusted workstation in a temporary directory outside the repository. They follow JetBrains' documented RSA signing flow:

```bash
openssl genpkey \
  -aes-256-cbc \
  -algorithm RSA \
  -out private_encrypted.pem \
  -pkeyopt rsa_keygen_bits:4096

openssl rsa \
  -in private_encrypted.pem \
  -out private.pem

openssl req \
  -key private.pem \
  -new \
  -x509 \
  -days 365 \
  -out chain.crt
```

Use the vendor identity in the certificate prompts. Save the following in one restricted 1Password item. Concealed fields avoid creating additional unencrypted local copies; attachments are also suitable when generated outside the repository and removed immediately afterward:

- `private_encrypted.pem` as the recovery key;
- `private.pem` as the signing key;
- `chain.crt` as the certificate chain;
- the private-key password as a concealed field;
- the certificate expiry date as a field or reminder.

Treat the certificate and key as release credentials. Never commit them, attach them to a release, paste them into an issue, or store them in a workflow artifact.

## 3. Create the Marketplace token

1. Open the Marketplace profile dashboard.
2. Open **My Tokens**.
3. Create a permanent token for MPH publishing.
4. Store it as a concealed field in the same 1Password item.

The token is only needed for automatic updates after the first manual plugin registration.

## 4. Configure the protected GitHub environment

Open the repository on GitHub and go to **Settings → Environments → release → Environment secrets**. Add:

| GitHub secret | Value from 1Password |
| --- | --- |
| `JETBRAINS_CERTIFICATE_CHAIN` | Complete contents of `chain.crt`, including the PEM header and footer. |
| `JETBRAINS_PRIVATE_KEY` | Complete contents of `private.pem`, including the PEM header and footer. |
| `JETBRAINS_PRIVATE_KEY_PASSWORD` | Password created while generating the key. |
| `JETBRAINS_MARKETPLACE_TOKEN` | Permanent Marketplace token. |

GitHub secrets accept multiline PEM content. The JetBrains Gradle plugin also accepts base64-encoded PEM values, but use one representation consistently. Keep the `release` environment protected; ordinary push and pull-request builds never receive these secrets.

The existing release environment variables and secrets remain required:

- `RELEASE_APP_CLIENT_ID` as an environment variable;
- `RELEASE_APP_PRIVATE_KEY` as an environment secret.

## 5. Bootstrap the first Marketplace release

1. Run the GitHub **Release** workflow from `develop`.
2. Select `bootstrap` for `marketplace_mode`.
3. Let the workflow test, verify, sign, tag, and publish the GitHub Release.
4. Download `mph-intellij-plugin-<version>.zip` from that GitHub Release.
5. In Marketplace, choose **Upload plugin** and upload the ZIP under the selected vendor.
6. Configure:
   - license: MIT;
   - source code: `https://github.com/mrhoeve/mph`;
   - issue tracker: `https://github.com/mrhoeve/mph/issues`;
   - release channel: Stable/default;
   - appropriate Maven, build tools, and version-management tags;
   - hidden visibility only if acceptance testing is required before public availability.
7. Submit the plugin for JetBrains approval.

JetBrains reviews new plugins and subsequent updates. Marketplace publication can therefore occur later than the corresponding GitHub Release.

## 6. Publish later versions automatically

For every later release, run the same **Release** workflow from `develop` with `marketplace_mode` set to `publish`.

The workflow then:

1. verifies the exact `develop` commit with all tests, Kover, Plugin Verifier, and SonarQube;
2. signs that verified ZIP and verifies its signature;
3. atomically updates `main`, `develop`, and the release tag;
4. attaches the signed ZIP to the GitHub Release;
5. uploads that same ZIP to the Stable Marketplace channel.

Once JetBrains approves the update, compatible IntelliJ IDEA installations discover it through the normal plugin update mechanism.

## Recovery

Marketplace publication intentionally runs after the Git tag and GitHub Release have been created. A temporary Marketplace outage therefore cannot invalidate the canonical release. After correcting a token or Marketplace problem, rerun only the failed GitHub Actions jobs. Before retrying an ambiguous upload failure, check the Marketplace version list to avoid submitting the same version twice.

