FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /source

# Install git
RUN apt-get update && apt-get install -y git

# Clone the repository
RUN git clone https://github.com/cashubtc/BTCNutServer.git .
RUN git submodule update --init --recursive

# Build the plugin
WORKDIR /source/Plugin/BTCPayServer.Plugins.Cashu
RUN dotnet publish -c Release -o /output/BTCPayServer.Plugins.Cashu
