import re

with open("app/src/main/res/values-es/strings.xml", "r") as f:
    content = f.read()

# Fix the merge conflict
content = re.sub(r'<<<<<<< HEAD.*?=======', '', content, flags=re.DOTALL)
content = re.sub(r'>>>>>>> .*?\n', '', content)

with open("app/src/main/res/values-es/strings.xml", "w") as f:
    f.write(content)


with open("app/src/main/res/values-es/strings_pos.xml", "r") as f:
    content = f.read()

content = re.sub(r'<<<<<<< HEAD.*?=======', '', content, flags=re.DOTALL)
content = re.sub(r'>>>>>>> .*?\n', '', content)

with open("app/src/main/res/values-es/strings_pos.xml", "w") as f:
    f.write(content)


with open("app/src/main/res/values-pt/strings.xml", "r") as f:
    content = f.read()

content = re.sub(r'<<<<<<< HEAD.*?=======', '', content, flags=re.DOTALL)
content = re.sub(r'>>>>>>> .*?\n', '', content)

with open("app/src/main/res/values-pt/strings.xml", "w") as f:
    f.write(content)

with open("app/src/main/res/values-pt/strings_pos.xml", "r") as f:
    content = f.read()

content = re.sub(r'<<<<<<< HEAD.*?=======', '', content, flags=re.DOTALL)
content = re.sub(r'>>>>>>> .*?\n', '', content)

with open("app/src/main/res/values-pt/strings_pos.xml", "w") as f:
    f.write(content)
