# Ghidra-EEC-806x

<!-- TABLE OF CONTENTS -->
<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#acknowledgements">Acknowledgements</a></li>
  </ol>
</details>

### Prerequisites

The EEC 806x processor module requires the following software to be installed:
* Java Development Kit (JDK)
* Ghidra 

### Installation

1. Change directory to where Ghidra is installed
2. Clone this repository into the Ghidra/Processors directory, rename to 806x:
   ```sh
   git clone https://github.com/mercury64/Ghidra-EEC-806x.git 806x
   ```
3. Any changes to the files within the processor, must be compiled:
   ``` sh
   ./support/sleigh -u -n -a Ghidra/Processors/806x/
   ```
4. Run Ghidra


## Usage

In Ghidra, create a new project folder.

Import your binary.

Select your processor language.

Enter options for your processor.

Click Ok, Ok, Ok, and double click your binary.

## Contributing

Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch
3. Commit your Changes
4. Push to the Branch
5. Open a Pull Request

## License

Released under the Apache 2.0 license. See `LICENSE` for more information.

## Acknowledgements
* [EFI Dyno Tuning](http://www.efidynotuning.com)
* [Semi Automatic Disassembler (SAD)](https://github.com/tvrfan/EEC-IV-disassembler)
* [Ghidra](https://ghidra-sre.org)
