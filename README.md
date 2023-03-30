# zprocess-resource
This is a little tool to handle Processes. 

When you call runProcess(command, environment, workingDirectory), it returns a ScopedProcess object, which contains a Java Process, that you can use, 
but (more importantly) it contains stdin, stdout, and stderr in ZStream form. 

You can read and write from them (be careful not to block, though)


